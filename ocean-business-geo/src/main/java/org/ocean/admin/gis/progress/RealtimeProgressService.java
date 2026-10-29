package org.ocean.admin.gis.progress;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.service.FileSpatialMetadataService;
import org.ocean.admin.kernel.realtime.EventMessage;
import org.ocean.admin.kernel.realtime.RealtimeEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.function.UnaryOperator;
import static org.ocean.admin.gis.progress.ProgressSnapshot.*;

/** 共享进度快照，统一用于消息推送和补查；不保存接入文件清单。 */
@Service
@Slf4j
public class RealtimeProgressService {
    private static final String PREFIX = "ocean-admin:progress:";
    private static final Duration RETENTION = Duration.ofMinutes(30);
    private static final Duration ACTIVE_RETENTION = Duration.ofHours(6);
    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);
    private final Map<String, String> executions = new ConcurrentHashMap<>();
    private final ProgressSnapshotProvider provider;
    private final RealtimeEventPublisher publisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RealtimeProgressService(ProgressSnapshotProvider provider,
            @Qualifier("gisRealtimeSsePublisher") RealtimeEventPublisher publisher,
            StringRedisTemplate redis, ObjectMapper json) {
        this.provider = provider; this.publisher = publisher; this.redis = redis; this.json = json;
    }

    public Handle create(String id, String correlation, String title, long total, String type, ProgressUnit unit) {
        return create(id, correlation, title, total, type, unit, null);
    }

    public Handle create(String id, String correlation, String title, long total, String type,
            ProgressUnit unit, Resource resource) {
        validateId(id);
        ProgressSnapshot existing = get(id);
        if (existing != null && ("GIS_UPLOAD".equals(type) || "GIS_WORKSPACE_IMPORT".equals(type))) return existing.handle();
        ProgressSnapshot snapshot = provider.rebuild(id);
        if (snapshot == null) {
            if (!Set.of("GIS_UPLOAD", "GIS_WORKSPACE_IMPORT").contains(type))
                throw new IllegalStateException("业务记录不存在，无法创建进度: " + id);
            snapshot = new ProgressSnapshot(id, correlation, type, null, title, TaskStatus.QUEUED,
                    Overall.of(null), ProgressStage.indeterminate("QUEUED", "已受理"), List.of(), List.of(),
                    new ProgressSummary(total, 0, 0, unit),
                    resource == null ? new Resource("INGESTION", id, "/api/gis/progress/" + id) : resource,
                    null, LocalDateTime.now(), null, new Revision(1, 0));
        }
        if (existing == null) {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + id, json.writeValueAsString(snapshot), ACTIVE_RETENTION)))
                return query(id).handle();
        } else redis.opsForValue().set(PREFIX + id, json.writeValueAsString(snapshot), ACTIVE_RETENTION);
        publish(snapshot, "gis.progress.created");
        return snapshot.handle();
    }

    public ProgressSnapshot get(String id) {
        String value = redis.opsForValue().get(PREFIX + id);
        return value == null ? null : json.readValue(value, ProgressSnapshot.class);
    }

    public ProgressSnapshot query(String id) {
        validateId(id);
        ProgressSnapshot snapshot = get(id);
        if (snapshot != null) {
            if (!snapshot.status().isTerminal()
                    && !Set.of("GIS_UPLOAD", "GIS_WORKSPACE_IMPORT").contains(snapshot.type())) {
                ProgressSnapshot authoritative = provider.load(id);
                if (authoritative != null && authoritative.status().isTerminal()
                        && authoritative.revision().epoch() == snapshot.revision().epoch()) {
                    update(snapshot.handle(), ProgressUpdate.summaryChanged(authoritative.summary()));
                    return finish(snapshot.handle(), authoritative.status(), authoritative.error() == null
                            ? "业务处理结束" : authoritative.error().message());
                }
            }
            return snapshot;
        }
        snapshot = provider.rebuild(id);
        if (snapshot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "进度已过期，请查询文件接入结果");
        redis.opsForValue().setIfAbsent(PREFIX + id, json.writeValueAsString(snapshot),
                snapshot.status().isTerminal() ? RETENTION : ACTIVE_RETENTION);
        return get(id);
    }

    public Handle handle(String id) { return query(id).handle(); }

    /** 请求与过期清理共享执行锁，分块文件仍以实际落盘偏移为准。 */
    public <T> T withExecutionLock(String id, Supplier<T> action) {
        String key = PREFIX + "execution:" + id, token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(60))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接入正在处理，请稍后重试");
        executions.put(id, token);
        try { return action.get(); }
        finally { executions.remove(id, token); redis.execute(UNLOCK, List.of(key), token); }
    }

    public void checkExecutionLease(String id) {
        if (!executions.containsKey(id)) throw new IllegalStateException("接入执行权已失效，请重新录入");
    }

    public void assertExecutionLock(String id) {
        String token = executions.get(id);
        if (token == null || !token.equals(redis.opsForValue().get(PREFIX + "execution:" + id)))
            throw new IllegalStateException("接入执行权已失效，请重新录入");
    }

    @Scheduled(fixedDelay = 10000)
    public void renewExecutions() {
        executions.forEach((id, token) -> {
            try {
                if (!Long.valueOf(1).equals(redis.execute(RENEW, List.of(PREFIX + "execution:" + id), token, "60000")))
                    executions.remove(id, token);
            } catch (RuntimeException ex) {
                executions.remove(id, token);
                log.warn("接入执行租约续期失败: {}", id, ex);
            }
        });
    }

    private ProgressSnapshot change(String id, UnaryOperator<ProgressSnapshot> update) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String before = redis.opsForValue().get(PREFIX + id);
            if (before == null) return null;
            ProgressSnapshot old = json.readValue(before, ProgressSnapshot.class);
            ProgressSnapshot next = update.apply(old);
            if (old == next) return old;
            long ttl = (next.status().isTerminal() ? RETENTION : ACTIVE_RETENTION).toMillis();
            if (Long.valueOf(1).equals(redis.execute(CAS, List.of(PREFIX + id), before,
                    json.writeValueAsString(next), Long.toString(ttl)))) {
                publish(next, next.status().isTerminal() ? "gis.progress.finished" : "gis.progress.updated");
                return next;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "进度更新冲突，请重试");
    }

    public void update(Handle handle, ProgressUpdate... updates) {
        if (updates == null || updates.length == 0) return;
        change(handle.progressId(), old -> {
            if (old.status().isTerminal() || old.revision().epoch() != handle.epoch()) return old;
            ProgressStage stage = old.stage();
            Integer percent = old.overall().percent();
            ProgressSummary summary = old.summary();
            List<FileWork> active = old.activeFiles();
            Map<String, Metric> metrics = new LinkedHashMap<>();
            old.metrics().forEach(metric -> metrics.put(metric.code(), metric));
            for (ProgressUpdate update : updates) {
                if (update instanceof ProgressUpdate.StageChanged value) stage = normalizeStage(value.stage());
                else if (update instanceof ProgressUpdate.OverallProgressChanged value) percent = value.percent();
                else if (update instanceof ProgressUpdate.ResultChanged value) summary = summary.withCounts(value.succeeded(), value.failed());
                else if (update instanceof ProgressUpdate.SummaryChanged value) summary = value.summary();
                else if (update instanceof ProgressUpdate.ActiveFilesChanged value) active = value.files();
                else if (update instanceof ProgressUpdate.MetricChanged value) metrics.put(value.metric().code(), value.metric());
            }
            return new ProgressSnapshot(old.progressId(), old.correlationId(), old.type(), old.category(), old.title(),
                    TaskStatus.RUNNING, Overall.of(percent), stage, List.copyOf(metrics.values()), active, summary,
                    old.resource(), old.error(), old.startedAt(), null, nextRevision(old));
        });
    }

    public ProgressSnapshot finish(Handle handle, TaskStatus status, String message) {
        if (!status.isTerminal()) throw new IllegalArgumentException("结束进度必须指定终态");
        return change(handle.progressId(), old -> {
            if (old.status().isTerminal() || old.revision().epoch() != handle.epoch()) return old;
            if (status != TaskStatus.FAILED && old.summary().processed() != old.summary().total())
                throw new IllegalStateException("仍有未处理项，不能结束进度");
            return new ProgressSnapshot(old.progressId(), old.correlationId(), old.type(), old.category(), old.title(), status,
                    Overall.of(old.summary().processed() == old.summary().total() ? Integer.valueOf(100) : old.overall().percent()),
                    ProgressStage.indeterminate(status.name(), message), old.metrics(), List.of(), old.summary(), old.resource(),
                    status == TaskStatus.COMPLETED ? null : new ErrorInfo("BUSINESS_FAILURE", message),
                    old.startedAt(), LocalDateTime.now(), nextRevision(old));
        });
    }

    /** 汇总数据库中的真实文件结果，不在Redis中维护文件历史。 */
    public void refreshIngestion(String id) {
        if (id == null) return;
        ProgressSnapshot result = change(id, old -> {
            if (old.status().isTerminal() || !"EXTRACTING_METADATA".equals(old.stage().code())) return old;
            ProgressSummary summary = provider.ingestionSummary(id, old);
            long registrationFailed = old.metrics().stream().filter(metric -> "REGISTRATION_FAILED".equals(metric.code()))
                    .mapToLong(Metric::value).findFirst().orElse(0);
            long total = summary.total() - summary.skipped() - registrationFailed;
            long processed = summary.succeeded() + summary.failed() - registrationFailed;
            boolean done = summary.processed() == summary.total();
            TaskStatus status = !done ? TaskStatus.RUNNING : summary.failed() == 0 ? TaskStatus.COMPLETED
                    : summary.succeeded() + summary.skipped() == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL_FAILED;
            String message = (done ? "接入结束" : "空间元数据解析中") + "，成功" + summary.succeeded()
                    + "个，失败" + summary.failed() + "个，跳过" + summary.skipped() + "个";
            ProgressStage stage = done || total == 0 ? ProgressStage.indeterminate(status.name(), message)
                    : ProgressStage.determinate("EXTRACTING_METADATA", message, processed, total, ProgressUnit.FILE);
            List<FileWork> active = old.activeFiles().stream().filter(work -> provider.isExtracting(work.fileId())).toList();
            return new ProgressSnapshot(old.progressId(), old.correlationId(), old.type(), old.category(), old.title(), status,
                    Overall.of(total == 0 ? (done ? 100 : null) : (int)(processed * 100 / total)), stage, old.metrics(),
                    done ? List.of() : active, summary, old.resource(),
                    done && summary.failed() > 0 ? new ErrorInfo("INGESTION_FAILED", message) : null,
                    old.startedAt(), done ? LocalDateTime.now() : null, nextRevision(old));
        });
        if (result != null && result.status().isTerminal()) provider.detachIngestion(id);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMetadataProgress(FileSpatialMetadataService.MetadataProgress event) {
        for (String id : provider.ingestionIds(event.fileIds())) {
            boolean interrupted = event.fileIds().stream().map(provider::ingestionFile).filter(Objects::nonNull)
                    .anyMatch(file -> id.equals(file.getIngestProgressId())
                            && "INGESTION_INTERRUPTED".equals(file.getMetadataErrorCode()));
            if (interrupted) {
                interruptIngestion(id);
            } else refreshIngestion(id);
        }
    }

    public void interruptIngestion(String id) {
        provider.failWaitingIngestion(id);
        ProgressSnapshot snapshot = get(id);
        if (snapshot != null && !snapshot.status().isTerminal()) {
            ProgressSummary actual = provider.ingestionSummary(id, snapshot);
            update(snapshot.handle(), ProgressUpdate.summaryChanged(new ProgressSummary(actual.total(), actual.succeeded(),
                    actual.total() - actual.succeeded() - actual.skipped(), actual.skipped(), ProgressUnit.FILE)));
            finish(snapshot.handle(), actual.succeeded() == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL_FAILED,
                    "接入中断，已登记文件保留，请删除失败记录后重新录入");
        }
        provider.detachIngestion(id);
    }

    @EventListener
    public void onMetadataWork(FileSpatialMetadataService.MetadataWork event) {
        GisFileMeta file = provider.ingestionFile(event.fileId());
        if (file == null || file.getIngestProgressId() == null || !"RUNNING".equals(file.getMetadataStatus())
                || !Objects.equals(event.version(), file.getMetadataVersion())) return;
        change(file.getIngestProgressId(), old -> {
            if (old.status().isTerminal() || !"EXTRACTING_METADATA".equals(old.stage().code())) return old;
            List<FileWork> active = new ArrayList<>(old.activeFiles());
            FileWork previous = active.stream().filter(work -> work.fileId().equals(file.getId().toString())).findFirst().orElse(null);
            if (previous != null && previous.runVersion() > event.version()) return old;
            active.removeIf(work -> work.fileId().equals(file.getId().toString()));
            active.add(new FileWork(file.getId().toString(), file.getOriginalName(), event.stage(), TaskStatus.RUNNING, event.version()));
            return new ProgressSnapshot(old.progressId(), old.correlationId(), old.type(), old.category(), old.title(), old.status(),
                    old.overall(), old.stage(), old.metrics(), active, old.summary(), old.resource(), old.error(),
                    old.startedAt(), old.finishedAt(), nextRevision(old));
        });
    }

    private Revision nextRevision(ProgressSnapshot snapshot) { return new Revision(snapshot.revision().epoch(), snapshot.revision().sequence() + 1); }
    private void publish(ProgressSnapshot snapshot, String type) {
        publisher.publish(EventMessage.create(type, "ocean.gis", "progress/" + snapshot.progressId(), snapshot.correlationId(), snapshot));
    }
    private void validateId(String id) {
        if (id == null || !id.matches("[a-z]+_[A-Za-z0-9_-]{8,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "进度编号无效");
    }
}
