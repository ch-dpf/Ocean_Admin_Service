package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisPreparedUpload;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.vo.GisUploadTaskVO;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** GIS 数据文件上传编排服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadService {

    private static final int MAX_FILES_PER_TASK = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "tif", "tiff", "img", "jp2", "png", "jpg", "jpeg", "tfw", "prj", "aux", "xml");
    private static final Set<String> TERRAIN_EXTENSIONS = Set.of(
            "tif", "tiff", "dem", "hgt", "terrain", "asc", "prj", "xml");
    private static final Set<String> VECTOR_EXTENSIONS = Set.of(
            "shp", "shx", "dbf", "prj", "cpg", "sbn", "sbx", "geojson", "json", "kml", "kmz", "gpkg");

    private final GisDataSetMapper gisDataSetMapper;
    private final GisTaskService gisTaskService;
    private final GisFileMetaService gisFileMetaService;
    private final TaskProgressService taskProgressService;
    private final FileStorageService fileStorageService;
    private final UploadTransactionService transactionService;
    private final FileUploadWorker uploadWorker;

    public GisUploadTaskVO createUploadTask(Long dataSetId, List<MultipartFile> files) {
        // 校验请求、生成任务编码
        GisDataSet dataSet = validateRequest(dataSetId, files);
        String taskNo = generateTaskNo();
        // 暂存待入库文件
        List<GisStagedFile> stagedFiles = new ArrayList<>(files.size());
        GisPreparedUpload preparedUpload = null;

        try {
            for (MultipartFile file : files) {
                validateFile(dataSet, file);
                stagedFiles.add(fileStorageService.stage(taskNo, file));
            }

            preparedUpload = transactionService.create(taskNo, dataSet, stagedFiles);
            taskProgressService.registerTask(
                    taskNo,
                    "上传数据集：" + dataSet.getDataSetName(),
                    stagedFiles.size(),
                    "GIS_UPLOAD");
            uploadWorker.process(preparedUpload);

            return GisUploadTaskVO.builder()
                    .taskId(preparedUpload.taskId())
                    .taskNo(taskNo)
                    .dataSetId(dataSetId)
                    .totalCount(stagedFiles.size())
                    .status("QUEUED")
                    .build();
        } catch (Exception ex) {
            if (preparedUpload != null) {
                gisTaskService.markFailed(preparedUpload.taskId(), ex.getMessage());
                gisFileMetaService.markTaskPendingFilesFailed(preparedUpload.taskId(), ex.getMessage());
                taskProgressService.finalizeTaskFailure(taskNo, "上传任务启动失败: " + ex.getMessage());
            }
            stagedFiles.forEach(file -> deleteStagedQuietly(file, taskNo));
            throw ex;
        }
    }

    private GisDataSet validateRequest(Long dataSetId, List<MultipartFile> files) {
        if (dataSetId == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("至少需要上传一个文件");
        }
        if (files.size() > MAX_FILES_PER_TASK) {
            throw new IllegalArgumentException("单次上传文件数量不能超过" + MAX_FILES_PER_TASK);
        }
        long totalSize = 0;
        for (MultipartFile file : files) {
            if (file != null) {
                totalSize = Math.addExact(totalSize, file.getSize());
            }
        }
        if (totalSize > MAX_TOTAL_SIZE) {
            throw new IllegalArgumentException("单次上传文件总大小不能超过5GB");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
        return dataSet;
    }

    private void validateFile(GisDataSet dataSet, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String name = file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            throw new IllegalArgumentException("文件扩展名不能为空: " + name);
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> allowed = switch (dataSet.getCategoryId().intValue()) {
            case 0 -> IMAGE_EXTENSIONS;
            case 1 -> TERRAIN_EXTENSIONS;
            case 2 -> VECTOR_EXTENSIONS;
            default -> throw new IllegalArgumentException("未知GIS数据类别: " + dataSet.getCategoryId());
        };
        if (!allowed.contains(extension)) {
            throw new IllegalArgumentException(
                    "文件类型与数据集类别不匹配: " + name + ", categoryId=" + dataSet.getCategoryId());
        }
    }

    private String generateTaskNo() {
        return "GIS_UPLOAD_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private void deleteStagedQuietly(GisStagedFile file, String taskNo) {
        try {
            fileStorageService.deleteStaged(file.getStagingKey());
        } catch (Exception cleanupEx) {
            log.warn("清理上传临时文件失败: taskNo={}, key={}",
                    taskNo, file.getStagingKey(), cleanupEx);
        }
    }
}
