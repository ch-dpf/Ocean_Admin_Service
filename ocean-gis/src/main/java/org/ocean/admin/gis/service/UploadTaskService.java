package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisPreparedUpload;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 在一个数据库事务中创建上传任务元数据及其文件元数据。 */
@Service
@RequiredArgsConstructor
public class UploadTaskService {

    private final GisTaskService gisTaskService;
    private final GisFileMetaService gisFileMetaService;

    @Transactional(rollbackFor = Exception.class)
    public GisPreparedUpload create(
            String taskNo,
            GisDataSet dataSet,
            List<GisStagedFile> stagedFiles) {
        GisTask task = GisTaskFactory.queued(taskNo,
                "上传数据集：" + dataSet.getDataSetName(), 1L, stagedFiles.size(), "STAGED");
        task.setDataSetId(dataSet.getId());
        gisTaskService.insert(task);

        List<GisUploadFileItem> items = gisFileMetaService.createPendingFiles(
                task.getId(), dataSet.getId(), stagedFiles);
        return new GisPreparedUpload(
                task.getId(), taskNo, dataSet.getId(), dataSet.getDataSetCode(), items);
    }
}
