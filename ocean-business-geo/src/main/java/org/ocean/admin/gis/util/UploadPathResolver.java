package org.ocean.admin.gis.util;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** 将 GIS 存储 key 限制在配置的上传根目录内。 */
@Component
public class UploadPathResolver {
    private final Path storageRoot;

    public UploadPathResolver(FileUploadConfig config) {
        this.storageRoot = Path.of(config.getBasePath()).toAbsolutePath().normalize();
    }

    public Path resolveKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("存储Key不能为空");
        }
        Path resolved = storageRoot.resolve(key.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("非法存储Key: " + key);
        }
        Path current = storageRoot;
        for (Path segment : storageRoot.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("非法存储Key: " + key);
            }
        }
        return resolved;
    }

    public String safeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("非法存储路径片段: " + value);
        }
        return value;
    }
}
