package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisPreparedUpload;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** 在一个数据库事务中创建上传任务及其文件明细。 */
@Service
@RequiredArgsConstructor
public class UploadTransactionService {

    private final GisTaskService gisTaskService;
    private final GisFileMetaService gisFileMetaService;

    @Transactional(rollbackFor = Exception.class)
    public GisPreparedUpload create(
            String taskNo,
            GisDataSet dataSet,
            List<GisStagedFile> stagedFiles) {
        LocalDateTime now = LocalDateTime.now();
        GisTask task = new GisTask();
        task.setTaskNo(taskNo);
        task.setTaskName("上传数据集：" + dataSet.getDataSetName());
        task.setTaskType(1L);
        task.setPriority(0);
        task.setTotalCount((long) stagedFiles.size());
        task.setCompletedCount(0L);
        task.setFailedCount(0L);
        task.setTaskStatus("QUEUED");
        task.setCurrentStage("STAGED");
        task.setDataSetId(dataSet.getId());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        gisTaskService.insert(task);

        List<GisUploadFileItem> items = gisFileMetaService.createPendingFiles(
                task.getId(), dataSet.getId(), stagedFiles);
        return new GisPreparedUpload(
                task.getId(), taskNo, dataSet.getId(), dataSet.getDataSetCode(), items);
    }
}
