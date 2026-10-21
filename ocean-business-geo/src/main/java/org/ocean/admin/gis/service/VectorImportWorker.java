package org.ocean.admin.gis.service;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 数据库待办是权威队列，空闲线程不足时留待下一次扫描。 */
@Component
@Slf4j
public class VectorImportWorker {
    private final GisVectorMapper mapper;
    private final VectorImportService service;
    private final Executor executor;

    public VectorImportWorker(GisVectorMapper mapper, VectorImportService service,
            @Qualifier("vectorExecutor") Executor executor) {
        this.mapper = mapper;
        this.service = service;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${gis.vector.poll-interval:5000}", initialDelayString = "${gis.vector.initial-delay:5000}")
    public void scan() {
        for (Long id : mapper.candidates(2)) {
            try {
                executor.execute(() -> {
                    try { service.process(id); }
                    catch (RuntimeException ex) { log.error("矢量导入后台任务异常: id={}", id, ex); }
                });
            } catch (RejectedExecutionException ex) { break; }
        }
    }
}
