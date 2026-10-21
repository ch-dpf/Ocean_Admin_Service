package org.ocean.admin.gis.service;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.mapper.GisVectorGeneralizationMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 存量图层自动登记；构建使用独立单槽执行器，数据库保存待办及恢复租约。 */
@Component
@Slf4j
public class VectorGeneralizationWorker {
    private final GisVectorGeneralizationMapper mapper;
    private final VectorGeneralizationService service;
    private final Executor executor;
    public VectorGeneralizationWorker(GisVectorGeneralizationMapper mapper,VectorGeneralizationService service,
            @Qualifier("generalizationExecutor") Executor executor) {
        this.mapper=mapper; this.service=service; this.executor=executor;
    }
    @Scheduled(fixedDelayString="${gis.vector.generalization.poll-interval:5000}",initialDelayString="${gis.vector.generalization.initial-delay:10000}")
    public void scan() {
        try {
            for (Long id:mapper.undiscovered(4)) service.markDirty(id);
            for (Long id:mapper.candidates(1)) {
                try { executor.execute(() -> service.process(id)); }
                catch (RejectedExecutionException ex) { break; }
            }
        } catch (RuntimeException ex) { log.warn("概化队列扫描失败: {}",ex.getMessage()); }
    }
}
