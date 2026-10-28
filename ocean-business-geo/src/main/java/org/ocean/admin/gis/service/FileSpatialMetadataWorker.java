package org.ocean.admin.gis.service;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 从数据库发现提取任务；空闲执行槽之外的任务继续保留在数据库中。 */
@Component
@Slf4j
public class FileSpatialMetadataWorker {
    private final GisFileSpatialMetadataMapper mapper;
    private final FileSpatialMetadataService service;
    private final Executor executor;

    public FileSpatialMetadataWorker(GisFileSpatialMetadataMapper mapper,
            FileSpatialMetadataService service, @Qualifier("metadataExecutor") Executor executor) {
        this.mapper = mapper;
        this.service = service;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${gis.metadata.poll-interval:5000}", initialDelayString = "${gis.metadata.initial-delay:5000}")
    public void scan() {
        service.expireWorkspaceWaiting();
        for (Long fileId : mapper.selectCandidates(2)) {
            if (!submit(fileId)) break;
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMetadataReady(FileSpatialMetadataService.MetadataReady event) {
        for (Long fileId : event.fileIds()) if (!submit(fileId)) break;
    }

    private boolean submit(Long fileId) {
        try {
            executor.execute(() -> {
                try { service.extract(fileId); }
                catch (RuntimeException ex) { log.error("空间元数据任务执行异常: fileId={}", fileId, ex); }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            return false;
        }
    }
}
