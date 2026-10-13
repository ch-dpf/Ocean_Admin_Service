package org.ocean.admin.gis.processing.input;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.service.GisFileMetaService;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 从文件元数据管理领域读取已入库文件。 */
@Component
@RequiredArgsConstructor
public class ManagedFileInputResolver implements GisProcessingInputResolver {
    private final GisFileMetaService fileMetaService;
    private final GisDataSetMapper dataSetMapper;
    private final FileUploadUtil fileUploadUtil;

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
            GisFileMeta meta = fileMetaService.getRequiredEntity(input.getFileMetaId());
            if (!"READY".equals(meta.getUploadStatus()) || !"LOCAL".equals(meta.getStorageType())) {
                throw new IllegalStateException("已管理文件当前不可用于处理: " + input.getFileMetaId());
            }
            return fileUploadUtil.resolveStoredPath(meta.getStorageKey());
        }).toList();
    }

    private GisFileMeta getValidMeta(Long id, GisSubmitProcessingCommand command) {
        GisFileMeta meta = fileMetaService.getRequiredEntity(id);
        if (!"READY".equals(meta.getUploadStatus()) || !"LOCAL".equals(meta.getStorageType())) {
            throw new IllegalStateException("当前只支持处理 READY 状态的 LOCAL 已管理文件: " + id);
        }
        GisDataSet dataSet = dataSetMapper.selectById(meta.getDataSetId());
        if (dataSet == null || !command.processingType().categoryId().equals(dataSet.getCategoryId())) {
            throw new IllegalArgumentException("处理类型与文件所属数据集类别不匹配: " + id);
        }
        return meta;
    }
}
