package org.ocean.admin.gis.service;

import org.ocean.admin.gis.config.GisStorageProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** 统一资产相对键与物理路径，拒绝越界、符号链接和目录联接。 */
@Service
public class GisStorageService {
    private final Path root;

    public GisStorageService(GisStorageProperties properties) {
        root = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
    }

    public Path resolveKey(String key) {
        if (key == null || key.isBlank() || key.contains("\\") || key.contains(":")
                || key.startsWith("/") || key.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("存储键必须是规范的相对路径");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IllegalArgumentException("存储键包含非法路径片段");
            }
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IllegalArgumentException("存储路径越界");
        // 校验包括根目录祖先，避免 Windows 联接或链接把文件操作重定向到其他目录。
        for (Path current = target; current != null; current = current.getParent()) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalArgumentException("存储路径禁止经过链接或特殊文件");
            } catch (NoSuchFileException ignored) {
                // 尚未创建的资产目录由实际写入方建立。
            } catch (IOException ex) {
                throw new IllegalArgumentException("无法校验存储路径", ex);
            }
        }
        return target;
    }

    /** 内部原生接口接收绝对路径时，先归一为同一根目录下的相对键再校验。 */
    public String relativeKey(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) throw new IllegalArgumentException("存储路径越界");
        String key = root.relativize(absolute).toString().replace('\\', '/');
        resolveKey(key);
        return key;
    }

    public Path resolveOriginal(String key) {
        if (key == null || !key.matches("(datasets/[1-9][0-9]*/originals|tilesets/[1-9][0-9]*/sources/uploads)/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("原始文件存储键不合法");
        }
        return resolveKey(key);
    }

    public String vectorKey(long dataSetId, long resourceId, String suffix) {
        if (dataSetId <= 0 || resourceId <= 0 || !java.util.Set.of("gpkg", "ndjson", "partial").contains(suffix)) {
            throw new IllegalArgumentException("矢量资源标识不合法");
        }
        return "datasets/" + dataSetId + "/vectors/" + resourceId + "." + suffix;
    }

    public String tileSetKey(long tileSetId) {
        if (tileSetId <= 0) throw new IllegalArgumentException("瓦片集标识不合法");
        return "tilesets/" + tileSetId;
    }

    public String safeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("非法存储路径片段");
        }
        return value;
    }

    public Path workspaceRoot(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("工作空间根目录不能为空");
        Path path = Path.of(value);
        if (path.isAbsolute()) return path.normalize();
        return resolveKey(value.replace('\\', '/'));
    }
}
