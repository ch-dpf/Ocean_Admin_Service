package org.ocean.admin.gis.processing.input;

import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.springframework.stereotype.Component;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.util.UploadPathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** 将工作空间编码和相对路径安全解析为服务器路径。 */
@Component
public class ControlledWorkspaceResolver {
    private final Map<String, Path> roots;
    private final UploadPathResolver uploads;

    public ControlledWorkspaceResolver(GisProcessingProperties properties, UploadPathResolver uploads) {
        this.uploads = uploads;
        this.roots = properties.getWorkspaces().entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> normalizeCode(entry.getKey()),
                        entry -> configuredRoot(entry.getKey(), entry.getValue())));
    }

    public Path resolve(String workspaceCode, String relativePath) {
        String code = normalizeCode(workspaceCode);
        if (!roots.containsKey(code)) {
            throw new IllegalArgumentException("未配置受控工作空间: " + workspaceCode);
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("工作空间相对路径不能为空");
        }
        Path relative;
        try {
            String value = relativePath.replace('\\', '/');
            if (value.startsWith("/") || value.contains(":") || value.chars().anyMatch(c -> c < 32)) {
                throw new IllegalArgumentException("工作空间输入必须为相对路径");
            }
            relative = Path.of(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("非法工作空间相对路径", ex);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("工作空间输入禁止使用绝对路径");
        }
        for (Path segment : relative) {
            if ("..".equals(segment.toString())) throw new IllegalArgumentException("工作空间路径禁止包含上级目录");
        }
        try {
            Path root = realRoot(code);
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root)) throw new IllegalArgumentException("工作空间路径越界");
            Path current = root;
            for (Path segment : root.relativize(resolved)) {
                current = current.resolve(segment);
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IllegalArgumentException("工作空间路径禁止经过链接或特殊文件");
                }
                if (!current.toRealPath().startsWith(root)) throw new IllegalArgumentException("工作空间路径越界");
            }
            if ((!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS))
                    || !Files.isReadable(resolved)) throw new IllegalArgumentException("工作空间输入不存在或不可读");
            return resolved.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("工作空间输入不存在或不可读");
        }
    }

    public String normalizeRelative(Path resolved, String workspaceCode) {
        Path root = realRoot(normalizeCode(workspaceCode));
        if (!resolved.normalize().startsWith(root)) {
            throw new IllegalArgumentException("工作空间路径不属于已配置根目录");
        }
        String relative = root.relativize(resolved.normalize()).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    /** 所有已登记文件的读取共用来源判别；工作区文件必须仍与登记快照一致。 */
    public Path resolveFile(GisFileMeta file) {
        if ("WORKSPACE".equals(file.getSourceType())) {
            try {
                Path path = resolve(file.getWorkspaceCode(), file.getRelativePath());
                BasicFileAttributes attributes = fileAttributes(path);
                if (file.getSourceModifiedAt() == null || file.getSizeBytes() == null
                        || file.getSizeBytes() != attributes.size()
                        || file.getSourceModifiedAt() != attributes.lastModifiedTime().toMillis()) {
                    throw new SourceException("SOURCE_CHANGED", "工作区源文件已变化，请删除记录后重新接入");
                }
                return path;
            } catch (SourceException ex) {
                throw ex;
            } catch (IllegalArgumentException ex) {
                throw new SourceException("SOURCE_UNAVAILABLE", "工作区源文件不存在、不可读或不再位于受控范围");
            }
        }
        if (!"UPLOAD".equals(file.getSourceType()) || !"LOCAL".equals(file.getStorageType())) {
            throw new IllegalArgumentException("当前仅支持本地托管文件和受控工作区文件");
        }
        Path path = uploads.resolveKey(file.getStorageKey());
        fileAttributes(path);
        return path;
    }

    public BasicFileAttributes fileAttributes(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !Files.isReadable(path)) throw new IllegalArgumentException("源文件不是可读普通文件");
            return attributes;
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取源文件属性");
        }
    }

    private Path realRoot(String code) {
        Path configured = roots.get(code);
        if (configured == null) throw new IllegalArgumentException("未配置受控工作空间: " + code);
        try {
            BasicFileAttributes attributes = Files.readAttributes(configured, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IllegalArgumentException("受控工作区根目录必须为普通目录");
            }
            return configured.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("受控工作区根目录不存在或不可读");
        }
    }

    public static class SourceException extends IllegalArgumentException {
        private final String code;

        public SourceException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    private static Path configuredRoot(String code, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工作空间根目录不能为空: " + code);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String normalizeCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("非法工作空间编码: " + code);
        }
        return code.toUpperCase(java.util.Locale.ROOT);
    }
}
