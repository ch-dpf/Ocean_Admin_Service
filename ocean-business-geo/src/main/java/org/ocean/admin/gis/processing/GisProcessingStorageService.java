package org.ocean.admin.gis.processing;

import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 为切片任务分配受控且可持久化引用的输出路径。 */
@Service
public class GisProcessingStorageService {

    private final Path processingRoot;

    public GisProcessingStorageService(GisProcessingProperties properties) {
        this.processingRoot = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
    }

    public GisProcessingWorkspace workspace(
            GisProcessingType type, Long fileMetaId, String taskNo) {
        if (type == null || fileMetaId == null || taskNo == null
                || !taskNo.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("无法创建非法的 GIS 处理工作目录");
        }
        String outputKey = type.name().toLowerCase(Locale.ROOT)
                + "/" + fileMetaId + "/" + taskNo;
        Path taskRoot = resolve(outputKey);
        return new GisProcessingWorkspace(
                outputKey,
                taskRoot.resolve("tiles"),
                taskRoot.resolve("temp"),
                taskRoot.resolve("logs").resolve("engine.log"));
    }

    /** 为不依赖文件元数据的服务器目录切片任务创建工作目录。 */
    public GisProcessingWorkspace folderWorkspace(GisProcessingType type, String taskNo) {
        if (type == null || taskNo == null || !taskNo.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("无法创建非法的 GIS 目录处理工作目录");
        }
        String outputKey = type.name().toLowerCase(Locale.ROOT)
                + "/folders/" + taskNo;
        Path taskRoot = resolve(outputKey);
        return new GisProcessingWorkspace(
                outputKey,
                taskRoot.resolve("tiles"),
                taskRoot.resolve("temp"),
                taskRoot.resolve("logs").resolve("engine.log"));
    }

    public GisProcessingWorkspace batchWorkspace(GisProcessingType type, String taskNo) {
        if (type == null || taskNo == null || !taskNo.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("无法创建非法的 GIS 多文件处理工作目录");
        }
        String outputKey = type.name().toLowerCase(Locale.ROOT) + "/batches/" + taskNo;
        Path taskRoot = resolve(outputKey);
        return new GisProcessingWorkspace(outputKey,
                taskRoot.resolve("tiles"), taskRoot.resolve("temp"),
                taskRoot.resolve("logs").resolve("engine.log"));
    }

    /** 每次处理任务分配一个与输入来源无关的瓦片集目录。 */
    public GisProcessingWorkspace taskWorkspace(GisProcessingType type, String taskNo) {
        if (type == null || taskNo == null || !taskNo.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("无法创建非法的 GIS 处理工作目录");
        }
        String outputKey = type.name().toLowerCase(Locale.ROOT) + "/tasks/" + taskNo;
        Path taskRoot = resolve(outputKey);
        return new GisProcessingWorkspace(outputKey,
                taskRoot.resolve("tiles"), taskRoot.resolve("temp"),
                taskRoot.resolve("logs").resolve("engine.log"));
    }

    /** 将数据库中的产物 Key 安全解析到切片目录。 */
    public Path resolveTiles(String outputKey) {
        if (outputKey == null || outputKey.isBlank()) {
            throw new IllegalArgumentException("切片产物 Key 不能为空");
        }
        return resolve(outputKey).resolve("tiles").normalize();
    }

    /** 为归档记录生成不包含用户输入的持久化 Key。 */
    public String archiveStorageKey(String archiveNo) {
        requireArchiveNo(archiveNo);
        return "archives/" + archiveNo + ".zip";
    }

    /** 安全解析数据库保存的静态瓦片集归档 Key。 */
    public Path resolveArchive(String storageKey) {
        if (storageKey == null || !storageKey.matches("archives/[A-Za-z0-9_.-]+\\.zip")) {
            throw new IllegalArgumentException("静态瓦片集归档 Key 不合法");
        }
        return resolve(storageKey);
    }

    /** 解析与正式归档同目录的临时文件，便于完成后原子移动。 */
    public Path resolveArchiveTemporary(String archiveNo) {
        requireArchiveNo(archiveNo);
        return resolve("archives/" + archiveNo + ".zip.part");
    }

    /** 根据持久化 outputKey 重建执行工作区。 */
    public GisProcessingWorkspace workspaceFromOutputKey(String outputKey) {
        if (outputKey == null || outputKey.isBlank()) {
            throw new IllegalArgumentException("切片产物 Key 不能为空");
        }
        Path taskRoot = resolve(outputKey);
        return new GisProcessingWorkspace(outputKey,
                taskRoot.resolve("tiles"), taskRoot.resolve("temp"),
                taskRoot.resolve("logs").resolve("engine.log"));
    }

    private Path resolve(String key) {
        Path resolved = processingRoot
                .resolve(key.replace('/', java.io.File.separatorChar))
                .normalize();
        if (!resolved.startsWith(processingRoot)) {
            throw new IllegalArgumentException("非法处理产物 Key: " + key);
        }
        return resolved;
    }

    /** 仅清理任务专属输出目录及归档文件，来源文件不在清理范围内。 */
    public void deleteTaskArtifacts(GisProcessingTask task, GisTileSet tileSet,
            List<GisTileSetArchive> archives) {
        String key = tileSet.getOutputKey();
        String prefix = task.getProcessingType().name().toLowerCase(Locale.ROOT) + "/";
        if (task.getTaskNo() == null || !task.getTaskNo().matches("[A-Za-z0-9_-]+")
                || key == null || !key.startsWith(prefix) || !key.endsWith("/" + task.getTaskNo())
                || key.contains("..") || key.contains("\\")) {
            throw new IllegalStateException("任务产物目录不合法，拒绝清理");
        }
        List<Path> targets = new ArrayList<>();
        targets.add(resolve(key));
        for (GisTileSetArchive archive : archives) {
            String expectedKey = archiveStorageKey(archive.getArchiveNo());
            if (archive.getStorageKey() != null && !expectedKey.equals(archive.getStorageKey())) {
                throw new IllegalStateException("归档产物目录不匹配，拒绝清理");
            }
            targets.add(resolveArchive(expectedKey));
            targets.add(resolveArchiveTemporary(archive.getArchiveNo()));
        }
        try {
            // 在删除任何文件之前验证所有目标和父目录，拒绝根目录及链接跳转。
            for (Path target : targets) {
                if (target.equals(processingRoot) || !target.startsWith(processingRoot)) {
                    throw new IllegalStateException("产物清理目标超出任务目录");
                }
                for (Path path = target; path != null && path.startsWith(processingRoot);
                        path = path.getParent()) {
                    try {
                        BasicFileAttributes attributes = Files.readAttributes(
                                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isSymbolicLink() || attributes.isOther()) {
                            throw new IllegalStateException("产物路径含链接或特殊文件，拒绝清理");
                        }
                    } catch (NoSuchFileException ignored) {
                        // 已不存在的产物允许重试清理，权限和其他存储错误必须向外抛出。
                    }
                }
            }
            for (Path target : targets) {
                try {
                    Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException ignored) {
                    continue;
                }
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
                            throws IOException {
                        if (!dir.toRealPath().startsWith(processingRoot.toRealPath())) {
                            throw new IOException("产物子目录超出处理存储范围");
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException ex) {
            throw new IllegalStateException("任务产物清理失败，部分文件可能已清理，请重试删除", ex);
        }
    }

    private void requireArchiveNo(String archiveNo) {
        if (archiveNo == null || !archiveNo.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("静态瓦片集归档编号不合法");
        }
    }
}
