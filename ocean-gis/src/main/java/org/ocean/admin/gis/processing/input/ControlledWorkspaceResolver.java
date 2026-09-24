package org.ocean.admin.gis.processing.input;

import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 将工作空间编码和相对路径安全解析为服务器路径。 */
@Component
public class ControlledWorkspaceResolver {
    private final Map<String, Path> roots;

    public ControlledWorkspaceResolver(GisProcessingProperties properties) {
        this.roots = properties.getWorkspaces().entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> normalizeCode(entry.getKey()),
                        entry -> configuredRoot(entry.getKey(), entry.getValue())));
    }

    public Path resolve(String workspaceCode, String relativePath) {
        String code = normalizeCode(workspaceCode);
        Path root = roots.get(code);
        if (root == null) {
            throw new IllegalArgumentException("未配置受控工作空间: " + workspaceCode);
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("工作空间相对路径不能为空");
        }
        Path relative;
        try {
            relative = Path.of(relativePath.trim().replace('\\', '/'));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("非法工作空间相对路径", ex);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("工作空间输入禁止使用绝对路径");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("工作空间路径越界: " + relativePath);
        }
        Path current = root;
        for (Path segment : root.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("工作空间路径禁止经过符号链接: " + relativePath);
            }
        }
        if ((!Files.isRegularFile(resolved) && !Files.isDirectory(resolved))
                || !Files.isReadable(resolved)) {
            throw new IllegalArgumentException("工作空间输入不存在或不可读: " + relativePath);
        }
        return resolved;
    }

    public String normalizeRelative(Path resolved, String workspaceCode) {
        Path root = roots.get(normalizeCode(workspaceCode));
        if (root == null || !resolved.normalize().startsWith(root)) {
            throw new IllegalArgumentException("工作空间路径不属于已配置根目录");
        }
        String relative = root.relativize(resolved.normalize()).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
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
