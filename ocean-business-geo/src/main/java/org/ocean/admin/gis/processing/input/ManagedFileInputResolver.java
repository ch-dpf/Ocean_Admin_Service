package org.ocean.admin.gis.processing.input;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.service.FileMetaService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 从文件元数据管理领域读取已入库文件。 */
@Component
@RequiredArgsConstructor
public class ManagedFileInputResolver implements GisProcessingInputResolver {
    private final FileMetaService fileMetaService;
    private final GisDataSetMapper dataSetMapper;

    @Override
    public GisInputSourceType type() {
        return GisInputSourceType.MANAGED_FILE;
    }

    @Override
    public PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo) {
        List<Long> ids = command.fileMetaIds();
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("文件元数据 ID 必须为1到100个且不能重复");
        }
        List<GisProcessingInput> inputs = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            GisFileMeta meta = getValidMeta(ids.get(i), command);
            GisProcessingInput input = new GisProcessingInput();
            input.setSequenceNo(i + 1);
            input.setInputKind("FILE");
            input.setInputStatus("READY");
            input.setFileMetaId(meta.getId());
            input.setSourceFileMetaId(meta.getId());
            input.setSourceDataSetId(meta.getDataSetId());
            input.setSourceDataSetName(requireDataSet(meta).getDataSetName());
            input.setSourceStorageType(meta.getStorageType());
            input.setSourceStorageKey(meta.getStorageKey());
            input.setSourceLocatorType(meta.getSourceType());
            input.setSourceModifiedAt(meta.getSourceModifiedAt());
            input.setWorkspaceCode(meta.getWorkspaceCode());
            input.setRelativePath(meta.getRelativePath());
            input.setOriginalName(meta.getOriginalName());
            input.setExtension(meta.getExtension());
            input.setSizeBytes(meta.getSizeBytes());
            input.setSha256(meta.getSha256());
            inputs.add(input);
        }
        return new PreparedInputs(inputs, null);
    }

    @Override
    public List<Path> resolveRuntime(List<GisProcessingInput> inputs) {
        return inputs.stream().map(input -> {
            GisFileMeta snapshot = new GisFileMeta();
            snapshot.setId(input.getSourceFileMetaId());
            snapshot.setSourceType(input.getSourceLocatorType());
            snapshot.setStorageType(input.getSourceStorageType());
            snapshot.setStorageKey(input.getSourceStorageKey());
            snapshot.setWorkspaceCode(input.getWorkspaceCode());
            snapshot.setRelativePath(input.getRelativePath());
            snapshot.setSourceModifiedAt(input.getSourceModifiedAt());
            snapshot.setSizeBytes(input.getSizeBytes());
            return fileMetaService.resolveSourcePath(snapshot);
        }).toList();
    }

    private GisFileMeta getValidMeta(Long id, GisSubmitProcessingCommand command) {
        GisFileMeta meta = fileMetaService.getRequiredEntity(id);
        if (!"READY".equals(meta.getFileStatus()) || !"COMPLETED".equals(meta.getMetadataStatus())) {
            throw new IllegalStateException("只支持处理已就绪且完成空间元数据提取的文件: " + id);
        }
        fileMetaService.resolveSourcePath(meta);
        GisDataSet dataSet = requireDataSet(meta);
        if (dataSet == null || !command.processingType().categoryId().equals(dataSet.getCategoryId())) {
            throw new IllegalArgumentException("处理类型与文件所属数据集类别不匹配: " + id);
        }
        return meta;
    }

    private GisDataSet requireDataSet(GisFileMeta meta) {
        GisDataSet dataSet = dataSetMapper.selectById(meta.getDataSetId());
        if (dataSet == null) {
            throw new IllegalArgumentException("文件所属数据集不存在: " + meta.getDataSetId());
        }
        return dataSet;
    }
}
