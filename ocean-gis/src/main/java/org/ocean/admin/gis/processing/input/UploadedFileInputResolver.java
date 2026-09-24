package org.ocean.admin.gis.processing.input;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 将 multipart 文件转存为异步任务可持久引用的输入。 */
@Component
@RequiredArgsConstructor
public class UploadedFileInputResolver implements GisProcessingInputResolver {
    private final FileUploadUtil fileUploadUtil;

    @Override
    public GisInputSourceType type() {
        return GisInputSourceType.UPLOAD;
    }

    @Override
    public PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo) {
        List<MultipartFile> files = command.uploadFiles();
        List<TempFile> staged = new ArrayList<>();
        List<GisStoredFile> stored = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                staged.add(fileUploadUtil.stage(taskNo, file));
            }
            List<GisProcessingInput> inputs = new ArrayList<>(staged.size());
            for (int i = 0; i < staged.size(); i++) {
                TempFile temp = staged.get(i);
                GisStoredFile persisted = fileUploadUtil.commitForProcessing(taskNo, temp);
                stored.add(persisted);
                inputs.add(input(i + 1, temp, persisted));
            }
            return new PreparedInputs(inputs, () -> stored.forEach(this::deleteQuietly));
        } catch (RuntimeException ex) {
            stored.forEach(this::deleteQuietly);
            staged.forEach(this::deleteStagedQuietly);
            throw ex;
        }
    }

    @Override
    public List<Path> resolveRuntime(List<GisProcessingInput> inputs) {
        return inputs.stream()
                .map(input -> fileUploadUtil.resolveStoredPath(input.getStorageKey()))
                .toList();
    }

    private GisProcessingInput input(int sequence, TempFile temp, GisStoredFile stored) {
        GisProcessingInput input = new GisProcessingInput();
        input.setSequenceNo(sequence);
        input.setInputKind("FILE");
        input.setInputStatus("READY");
        input.setStorageKey(stored.getStorageKey());
        input.setOriginalName(temp.getOriginalName());
        input.setExtension(temp.getExtension());
        input.setSizeBytes(stored.getSizeBytes());
        input.setSha256(stored.getSha256());
        return input;
    }

    private void deleteQuietly(GisStoredFile file) {
        try { fileUploadUtil.deleteStored(file.getStorageKey()); } catch (RuntimeException ignored) { }
    }

    private void deleteStagedQuietly(TempFile file) {
        try { fileUploadUtil.deleteStaged(file.getStagingKey()); } catch (RuntimeException ignored) { }
    }
}
