package org.ocean.admin.gis.progress;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.kernel.realtime.EventMessage;
import org.ocean.admin.kernel.realtime.RealtimeEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.ocean.admin.gis.progress.ProgressSnapshot.*;

/** 长耗时业务的状态投影；业务结果由调用方决定，快照不作为调度队列。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RealtimeProgressService {
    private static final String CACHE_PREFIX = "ocean-admin:progress:v1:";
    private static final Duration RETENTION = Duration.ofMinutes(30);
    private static final Duration INACTIVE_TIMEOUT = Duration.ofHours(6);
    private static final long PUSH_INTERVAL = TimeUnit.MILLISECONDS.toNanos(500);
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final ProgressSnapshotProvider provider;
    private final RealtimeEventPublisher publisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 首次创建及明确重试时调用；持久化流程从数据库分配新代次。 */
    public Handle create(String id, String correlationId, String title, long total,
                         String type, ProgressUnit unit) {
        return create(id, correlationId, title, total, type, unit, null);
    }

    public Handle create(String id, String correlationId, String title, long total,
                         String type, ProgressUnit unit, Resource resource) {
        validateId(id);
        synchronized (lock(id)) {
            ProgressSnapshot snapshot = provider.rebuild(id);
            if (snapshot == null) {
                if (!"GIS_WORKSPACE_IMPORT".equals(type))
                    throw new IllegalStateException("业务记录不存在，无法创建进度: " + id);
                snapshot = new ProgressSnapshot(id, correlationId, type, null, title, TaskStatus.QUEUED,
                        Overall.of(null), ProgressStage.indeterminate("QUEUED", "已受理"),
                        List.of(), new ProgressSummary(total, 0, 0, unit),
                        resource == null ? new Resource("WORKSPACE_IMPORT", id, "/api/gis/progress/" + id) : resource, null,
                        LocalDateTime.now(), null, new Revision(1, 0));
            }
            State state = new State(snapshot);
            states.put(id, state);
            persist(snapshot);
            publish(snapshot, "gis.progress.created");
            return snapshot.handle();
        }
    }

    public ProgressSnapshot get(String id) {
        synchronized (lock(id)) {
            State state = states.get(id);
            if (state == null) return null;
            if (expired(state)) {
                states.remove(id, state);
                return null;
            }
            return state.snapshot;
        }
    }

    /** 查询只在本地快照缺失时重建；工作区运行态无法跨进程恢复。 */
    public ProgressSnapshot query(String id) {
        validateId(id);
        synchronized (lock(id)) {
            ProgressSnapshot snapshot = get(id);
            if (snapshot != null) {
                if (!snapshot.status().isTerminal()) {
                    ProgressSnapshot authoritative = provider.load(id);
                    if (authoritative != null && authoritative.status().isTerminal()
                            && authoritative.revision().epoch() == snapshot.revision().epoch()) {
                        update(snapshot.handle(), ProgressUpdate.summaryChanged(authoritative.summary()));
                        return finish(snapshot.handle(), authoritative.status(),
                                authoritative.error() == null ? "业务已结束，补查恢复终态" : authoritative.error().message());
                    }
                }
                return snapshot;
            }
            snapshot = readTerminalCache(id);
            boolean restoredTerminal = snapshot != null;
            if (snapshot == null) snapshot = provider.rebuild(id);
            if (snapshot == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        id.startsWith("workspace_") ? "PROGRESS_EXPIRED: 工作区进度已过期或服务已重启" : "进度不存在");
            State state = new State(snapshot);
            if (restoredTerminal) state.updatedAt = snapshot.finishedAt();
            states.put(id, state);
            persist(snapshot);
            return snapshot;
        }
    }

    public Handle handle(String id) {
        ProgressSnapshot snapshot = get(id);
        return (snapshot == null ? query(id) : snapshot).handle();
    }

    public void update(Handle handle, ProgressUpdate... updates) {
        synchronized (lock(handle.progressId())) {
            State state = states.get(handle.progressId());
            if (state == null || updates == null || updates.length == 0) return;
            ProgressSnapshot old = state.snapshot;
            if (!owns(state, handle) || old.status().isTerminal()) return;
            ProgressStage stage = old.stage();
            Integer percent = old.overall().percent();
            ProgressSummary summary = old.summary();
            Map<String, Metric> metrics = new LinkedHashMap<>();
            old.metrics().forEach(m -> metrics.put(m.code(), m));
            for (ProgressUpdate update : updates) {
                if (update instanceof ProgressUpdate.StageChanged changed) {
                    stage = normalizeStage(changed.stage());
                    if (stage.completed() != null)
                        metrics.put("STAGE_" + stage.code(), new Metric("STAGE_" + stage.code(),
                                stage.code(), stage.completed(), stage.total(), stage.unit()));
                } else if (update instanceof ProgressUpdate.OverallProgressChanged changed) {
                    percent = changed.percent();
                } else if (update instanceof ProgressUpdate.ResultChanged changed) {
                    summary = summary.withCounts(changed.succeeded(), changed.failed());

                } else if (update instanceof ProgressUpdate.MetricChanged changed) {
                    metrics.put(changed.metric().code(), changed.metric());
                } else if (update instanceof ProgressUpdate.SummaryChanged changed) {
                    summary = changed.summary();
                } else {
                    throw new IllegalArgumentException("进度变更不能为空");
                }
            }
            boolean stageChanged = !stage.code().equals(old.stage().code());
            state.snapshot = new ProgressSnapshot(old.progressId(), old.correlationId(), old.type(), old.category(),
                    old.title(), TaskStatus.RUNNING, Overall.of(percent), stage, new ArrayList<>(metrics.values()),
                    summary, old.resource(), old.error(), old.startedAt() == null ? LocalDateTime.now() : old.startedAt(),
                    null, new Revision(old.revision().epoch(), old.revision().sequence() + 1));
            state.updatedAt = LocalDateTime.now();
            state.dirty = true;
            persist(state.snapshot);
            flush(state, stageChanged);
        }
    }

    public ProgressSnapshot finish(Handle handle, TaskStatus status, String message) {
        if (!status.isTerminal()) throw new IllegalArgumentException("结束进度必须指定终态");
        synchronized (lock(handle.progressId())) {
            State state = states.get(handle.progressId());
            if (state == null) return null;
            ProgressSnapshot old = state.snapshot;
            if (!owns(state, handle)) return null;
            if (old.status().isTerminal()) return old;
            if (status != TaskStatus.FAILED && old.summary().processed() != old.summary().total())
                throw new IllegalStateException("仍有未处理项，不能正常结束进度");
            if (status == TaskStatus.COMPLETED && old.summary().failed() != 0)
                throw new IllegalStateException("存在失败项，不能发布全部成功");
            state.snapshot = new ProgressSnapshot(old.progressId(), old.correlationId(), old.type(), old.category(),
                    old.title(), status, Overall.of(status == TaskStatus.COMPLETED ? Integer.valueOf(100) : old.overall().percent()),
                    ProgressStage.indeterminate(status.name(), message), old.metrics(), old.summary(), old.resource(),
                    status == TaskStatus.FAILED || status == TaskStatus.PARTIAL_FAILED
                            ? new ErrorInfo("BUSINESS_FAILURE", message) : null,
                    old.startedAt(), LocalDateTime.now(),
                    new Revision(old.revision().epoch(), old.revision().sequence() + 1));
            state.updatedAt = LocalDateTime.now();
            state.dirty = false;
            persist(state.snapshot);
            publish(state.snapshot, "gis.progress.finished");
            return state.snapshot;
        }
    }

    private Object lock(String id) {
        return locks[Math.floorMod(id.hashCode(), locks.length)];
    }

    private boolean owns(State state, Handle handle) {
        return states.get(handle.progressId()) == state
                && state.snapshot.revision().epoch() == handle.epoch();
    }

    @Scheduled(fixedDelay = 500)
    public void flushPending() {
        for (State state : states.values()) synchronized (lock(state.snapshot.progressId())) {
            if (states.get(state.snapshot.progressId()) != state) continue;
            if (expired(state)) {
                states.remove(state.snapshot.progressId(), state);
            } else if (state.dirty) flush(state, false);
        }
    }

    private void flush(State state, boolean force) {
        long now = System.nanoTime();
        if (!force && now - state.lastPush < PUSH_INTERVAL) return;
        state.lastPush = now;
        state.dirty = false;
        publish(state.snapshot, "gis.progress.updated");
    }

    private boolean expired(State state) {
        Duration retention = state.snapshot.status().isTerminal() ? RETENTION : INACTIVE_TIMEOUT;
        return state.updatedAt.plus(retention).isBefore(LocalDateTime.now());
    }

    private ProgressSnapshot readTerminalCache(String id) {
        if (id.startsWith("workspace_")) return null;
        try {
            String json = redis.opsForValue().get(CACHE_PREFIX + id);
            if (json == null) return null;
            ProgressSnapshot cached = objectMapper.readValue(json, ProgressSnapshot.class);
            if (!id.equals(cached.progressId()) || !cached.status().isTerminal()
                    || cached.finishedAt().plus(RETENTION).isBefore(LocalDateTime.now())) return null;
            ProgressSnapshot authoritative = provider.load(id);
            return authoritative != null && authoritative.status() == cached.status()
                    && authoritative.revision().epoch() == cached.revision().epoch() ? cached : null;
        } catch (RuntimeException ex) {
            log.warn("终态缓存读取失败，将从业务记录重建: progressId={}, reason={}", id, ex.getMessage());
            return null;
        }
    }

    private void persist(ProgressSnapshot snapshot) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + snapshot.progressId(),
                    objectMapper.writeValueAsString(snapshot),
                    snapshot.status().isTerminal() ? RETENTION : INACTIVE_TIMEOUT);
        } catch (RuntimeException ex) {
            log.warn("进度缓存写入失败: progressId={}, reason={}", snapshot.progressId(), ex.getMessage());
        }
    }

    private void publish(ProgressSnapshot snapshot, String type) {
        try {
            publisher.publish(EventMessage.create(type, "ocean.gis", "progress/" + snapshot.progressId(),
                    snapshot.correlationId(), snapshot));
        } catch (RuntimeException ex) {
            log.warn("进度通知失败，快照可补查: progressId={}, reason={}", snapshot.progressId(), ex.getMessage());
        }
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[a-z]+_[0-9]{14}_[a-f0-9]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "进度编号无效");
    }

    private static final class State {
        ProgressSnapshot snapshot;
        LocalDateTime updatedAt = LocalDateTime.now();
        long lastPush;
        boolean dirty;
        State(ProgressSnapshot snapshot) {
            this.snapshot = snapshot;

        }
    }
}
