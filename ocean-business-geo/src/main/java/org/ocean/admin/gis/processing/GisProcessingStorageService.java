package org.ocean.admin.gis.processing;

import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.service.GisStorageService;
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

/** 为切片任务分配受控且可持久化引用的输出路径。 */
@Service
public class GisProcessingStorageService {

    private final GisStorageService storage;

    public GisProcessingStorageService(GisStorageService storage) {
        this.storage = storage;
    }

    /** 成果归属瓦片集，执行现场按任务独立保存。 */
    public GisProcessingWorkspace taskWorkspace(long tileSetId, String taskNo) {
        return workspaceFromOutputKey(storage.tileSetKey(tileSetId), taskNo);
    }

    public GisProcessingWorkspace workspaceFromOutputKey(String outputKey, String taskNo) {
        requireTileSetKey(outputKey);
        Path runtime = storage.resolveKey("runtime/processing/" + storage.safeSegment(taskNo));
        return new GisProcessingWorkspace(outputKey, resolveTiles(outputKey),
                runtime.resolve("temp"), runtime.resolve("logs/engine.log"));
    }

    public Path resolveTiles(String outputKey) {
        requireTileSetKey(outputKey);
        return storage.resolveKey(outputKey + "/tiles");
    }

    /** 发布依赖的输入快照随瓦片集保留，不参与任务临时文件清理。 */
    public Path resolveSources(String outputKey) {
        requireTileSetKey(outputKey);
        return storage.resolveKey(outputKey + "/sources");
    }

    public String archiveStorageKey(long tileSetId, String archiveNo) {
        requireArchiveNo(archiveNo);
        return storage.tileSetKey(tileSetId) + "/archives/" + archiveNo + ".zip";
    }

    public Path resolveArchive(String storageKey) {
        if (storageKey == null || !storageKey.matches("tilesets/[1-9][0-9]*/archives/[A-Za-z0-9_.-]+\\.zip")) {
            throw new IllegalArgumentException("静态瓦片集归档 Key 不合法");
        }
        return storage.resolveKey(storageKey);
    }

    public Path resolveArchiveTemporary(long tileSetId, String archiveNo) {
        return storage.resolveKey(archiveStorageKey(tileSetId, archiveNo) + ".part");
    }

    private void requireTileSetKey(String key) {
        if (key == null || !key.matches("tilesets/[1-9][0-9]*")) {
            throw new IllegalArgumentException("瓦片集 Key 不合法");
        }
    }

    /** 仅清理任务专属输出目录及归档文件，来源文件不在清理范围内。 */
    public void deleteTaskArtifacts(GisProcessingTask task, GisTileSet tileSet,
            List<GisTileSetArchive> archives) {
        String key = tileSet.getOutputKey();
        if (!storage.tileSetKey(tileSet.getId()).equals(key)) {
            throw new IllegalStateException("瓦片集产物目录与资产标识不匹配，拒绝清理");
        }
        List<Path> targets = new ArrayList<>();
        targets.add(storage.resolveKey(key));
        targets.add(storage.resolveKey("runtime/processing/" + storage.safeSegment(task.getTaskNo())));
        for (GisTileSetArchive archive : archives) {
            String expectedKey = archiveStorageKey(tileSet.getId(), archive.getArchiveNo());
            if (archive.getStorageKey() != null && !expectedKey.equals(archive.getStorageKey())) {
                throw new IllegalStateException("归档产物目录不匹配，拒绝清理");
            }
            targets.add(resolveArchive(expectedKey));
            targets.add(resolveArchiveTemporary(tileSet.getId(), archive.getArchiveNo()));
        }
        Path processingRoot = storage.resolveKey("tilesets").getParent();
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
