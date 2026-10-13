package org.ocean.admin.gis.processing.input;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 解析配置白名单内的服务器文件或目录。 */
@Component
@RequiredArgsConstructor
public class WorkspaceInputResolver implements GisProcessingInputResolver {
    private final ControlledWorkspaceResolver workspaceResolver;

    @Override
    public GisInputSourceType type() {
        return GisInputSourceType.WORKSPACE;
    }

    @Override
    public PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo) {
        Path path = workspaceResolver.resolve(command.workspaceCode(), command.relativePath());
        GisProcessingInput input = new GisProcessingInput();
        input.setSequenceNo(1);
        input.setInputKind(Files.isDirectory(path) ? "DIRECTORY" : "FILE");
        input.setInputStatus("READY");
        input.setWorkspaceCode(command.workspaceCode().toUpperCase(java.util.Locale.ROOT));
        input.setRelativePath(workspaceResolver.normalizeRelative(path, command.workspaceCode()));
        input.setOriginalName(path.getFileName() == null ? input.getRelativePath()
                : path.getFileName().toString());
        if (Files.isRegularFile(path)) {
            input.setExtension(FileUploadUtil.extensionOf(input.getOriginalName()));
            try {
                input.setSizeBytes(Files.size(path));
            } catch (IOException ex) {
                throw new IllegalArgumentException("无法读取工作空间文件大小", ex);
            }
        }
        return new PreparedInputs(List.of(input), null);
    }

    @Override
    public List<Path> resolveRuntime(List<GisProcessingInput> inputs) {
        return inputs.stream()
                .map(input -> workspaceResolver.resolve(
                        input.getWorkspaceCode(), input.getRelativePath()))
                .toList();
    }
}
