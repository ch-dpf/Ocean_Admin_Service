package org.ocean.admin.gis.util;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongConsumer;

/** GIS 文件上传的批量校验与文件暂存、转存。 */
@Slf4j
@Component
public class FileUploadUtil {

    private static final long MAX_FILE_SIZE = 500L * 1024 * 1024;
    private final UploadPathResolver pathResolver;
    private final int streamBufferSize;

    public FileUploadUtil(UploadPathResolver pathResolver, FileUploadConfig uploadConfig) {
        this.pathResolver = pathResolver;
        long configuredBufferSize = uploadConfig.getStreamBufferSize().toBytes();
        if (configuredBufferSize < 8 * 1024 || configuredBufferSize > 16L * 1024 * 1024) {
            throw new IllegalArgumentException("GIS上传流缓冲区必须在8KiB到16MiB之间");
        }
        this.streamBufferSize = Math.toIntExact(configuredBufferSize);
    }

    /** 单个请求块写盘后的状态。 */
    public record ChunkWriteResult(long nextOffset, boolean duplicate, String sha256) {
        public boolean fileCompleted() {
            return sha256 != null;
        }
    }

    public static void validateBatch(List<MultipartFile> files, int maxFiles, long maxTotalSize,
            String countMessage, String emptyFileMessage, String sizeMessage) {
        if (files == null || files.isEmpty() || files.size() > maxFiles) {
            throw new IllegalArgumentException(countMessage);
        }
        long totalSize = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException(emptyFileMessage);
            }
            long fileSize = file.getSize();
            if (fileSize > maxTotalSize - totalSize) {
                throw new IllegalArgumentException(sizeMessage);
            }
            totalSize += fileSize;
        }
    }

    /** 在 HTTP 请求生命周期内将 MultipartFile 转存到受控临时目录。 */
    public TempFile stage(String taskNo, MultipartFile file) {
        return stage(taskNo, file, ignored -> { });
    }

    /** 在暂存过程中按已写入字节回报进度。 */
    public TempFile stage(String taskNo, MultipartFile file, LongConsumer progressListener) {
        validateMultipartFile(file);
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String storageName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String stagingKey = ".staging/" + taskNo + "/" + storageName;
        Path target = resolveKey(stagingKey);

        try {
            Files.createDirectories(target.getParent());
            String sha256 = copyWithDigest(
                    taskNo, originalName, file, target, progressListener);
            return TempFile.builder()
                    .originalName(originalName)
                    .storageName(storageName)
                    .stagingKey(stagingKey)
                    .extension(extension)
                    .sizeBytes(Files.size(target))
                    .sha256(sha256)
                    .contentType(file.getContentType())
                    .build();
        } catch (IOException | NoSuchAlgorithmException ex) {
            deleteQuietly(target);
            throw new IllegalStateException("文件暂存失败: " + originalName, ex);
        }
    }

    /** 直接写入数据集正式目录，写入完成前使用 .part 后缀隔离半成品。 */
    public GisStoredFile store(
            Long dataSetId,
            String taskNo,
            MultipartFile file,
            LongConsumer progressListener) {
        validateMultipartFile(file);
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String storageName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String storageKey = dataSetStorageKey(dataSetId, storageName);
        Path target = resolveKey(storageKey);
        Path partial = target.resolveSibling(target.getFileName() + ".part");

        try {
            Files.createDirectories(target.getParent());
            String sha256 = copyWithDigest(
                    taskNo, originalName, file, partial, progressListener);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(partial, target);
            }
            return GisStoredFile.builder()
                    .storageName(storageName)
                    .storageKey(storageKey)
                    .storageType("LOCAL")
                    .sizeBytes(Files.size(target))
                    .sha256(sha256)
                    .build();
        } catch (IOException | NoSuchAlgorithmException ex) {
            deleteQuietly(partial);
            deleteQuietly(target);
            throw new IllegalStateException("文件存储失败: " + originalName, ex);
        }
    }

    /** 将请求期暂存文件转为可供异步处理的持久输入。 */
    public GisStoredFile commitForProcessing(String taskNo, TempFile stagedFile) {
        String storageKey = "processing/" + pathResolver.safeSegment(taskNo) + "/"
                + pathResolver.safeSegment(stagedFile.getStorageName());
        Path source = resolveKey(stagedFile.getStagingKey());
        Path target = resolveKey(storageKey);
        try {
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("暂存文件不存在: " + stagedFile.getStagingKey());
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, target);
            }
            return GisStoredFile.builder()
                    .storageName(stagedFile.getStorageName())
                    .storageKey(storageKey)
                    .storageType("LOCAL")
                    .sizeBytes(stagedFile.getSizeBytes())
                    .sha256(stagedFile.getSha256())
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("处理输入文件转存失败: " + stagedFile.getOriginalName(), ex);
        }
    }

    /**
     * 将一个应用层分块流式追加到任务暂存文件。
     *
     * <p>允许客户端重试已经写入或部分写入的块，但会逐字节校验重叠区，避免同一 offset
     * 被不同内容覆盖。只有完整读完声明的 chunkSize 后才保留本次新增内容。</p>
     */
    public ChunkWriteResult appendChunk(
            String taskNo,
            int fileIndex,
            long expectedOffset,
            long fileSize,
            long chunkSize,
            InputStream input,
            LongConsumer progressListener) {
        if (input == null) {
            throw new IllegalArgumentException("上传数据流不能为空");
        }
        if (expectedOffset < 0 || fileSize < 1 || chunkSize < 1
                || expectedOffset + chunkSize > fileSize) {
            throw new IllegalArgumentException("上传分块范围不合法");
        }

        Path partial = uploadPartialPath(taskNo, fileIndex);
        try {
            Files.createDirectories(partial.getParent());
            long originalSize = Files.exists(partial) ? Files.size(partial) : 0L;
            if (originalSize > fileSize) {
                throw new IllegalStateException("服务端暂存文件大小超过声明文件大小");
            }
            if (expectedOffset > originalSize) {
                throw new IllegalStateException(
                        "上传偏移不连续，服务端下一偏移为" + originalSize);
            }

            boolean duplicate = expectedOffset + chunkSize <= originalSize;
            byte[] incoming = new byte[streamBufferSize];
            byte[] existing = new byte[streamBufferSize];
            long requestRead = 0L;
            try (FileChannel channel = FileChannel.open(
                    partial,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                while (requestRead < chunkSize) {
                    int wanted = (int) Math.min(incoming.length, chunkSize - requestRead);
                    int read = input.read(incoming, 0, wanted);
                    if (read < 0) {
                        throw new IllegalArgumentException(
                                "上传分块内容短于声明大小: " + requestRead + "/" + chunkSize);
                    }
                    if (read == 0) {
                        continue;
                    }

                    long absolutePosition = expectedOffset + requestRead;
                    int overlap = (int) Math.min(read, Math.max(0L, originalSize - absolutePosition));
                    if (overlap > 0) {
                        readFully(channel, existing, overlap, absolutePosition);
                        for (int index = 0; index < overlap; index++) {
                            if (incoming[index] != existing[index]) {
                                throw new IllegalArgumentException(
                                        "重试分块与服务端已落盘内容不一致，offset="
                                                + (absolutePosition + index));
                            }
                        }
                    }
                    if (overlap < read) {
                        writeFully(
                                channel,
                                ByteBuffer.wrap(incoming, overlap, read - overlap),
                                absolutePosition + overlap);
                    }
                    requestRead += read;
                    if (progressListener != null) {
                        try {
                            progressListener.accept(
                                    Math.max(originalSize, expectedOffset + requestRead));
                        } catch (RuntimeException progressEx) {
                            log.debug("上传分块实时进度回报失败: taskNo={}, fileIndex={}, error={}",
                                    taskNo, fileIndex, progressEx.getMessage());
                        }
                    }
                }
                if (input.read() != -1) {
                    throw new IllegalArgumentException("上传分块内容长于声明大小: " + chunkSize);
                }
                channel.force(false);
            } catch (IOException | RuntimeException ex) {
                truncateQuietly(partial, originalSize);
                throw ex;
            }

            long nextOffset = Files.size(partial);
            if (nextOffset > fileSize) {
                truncateQuietly(partial, originalSize);
                throw new IllegalStateException("上传分块导致暂存文件越界");
            }
            String sha256 = nextOffset == fileSize ? sha256(partial) : null;
            return new ChunkWriteResult(nextOffset, duplicate, sha256);
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("上传分块写盘失败", ex);
        }
    }

    /** 将完整暂存文件原子转入数据集正式目录；目标名由任务与文件序号确定，便于故障重试。 */
    public GisStoredFile completeStagedFile(
            Long dataSetId,
            String taskNo,
            int fileIndex,
            String originalName,
            long fileSize,
            String sha256) {
        Path source = uploadPartialPath(taskNo, fileIndex);
        String storageName = uploadStorageName(taskNo, fileIndex, originalName);
        String storageKey = dataSetStorageKey(dataSetId, storageName);
        Path target = resolveKey(storageKey);
        try {
            if (Files.isRegularFile(target) && Files.size(target) == fileSize) {
                return storedFile(storageName, storageKey, fileSize, sha256(target));
            }
            if (!Files.isRegularFile(source) || Files.size(source) != fileSize) {
                throw new IllegalStateException("上传暂存文件尚未完整落盘");
            }
            Files.createDirectories(target.getParent());
            move(source, target);
            return storedFile(storageName, storageKey, fileSize, sha256);
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("上传文件转入正式目录失败: " + originalName, ex);
        }
    }

    /** 识别“文件已转正但数据库事务尚未提交”的可恢复窗口。 */
    public GisStoredFile findCompletedFile(
            Long dataSetId,
            String taskNo,
            int fileIndex,
            String originalName,
            long fileSize) {
        String storageName = uploadStorageName(taskNo, fileIndex, originalName);
        String storageKey = dataSetStorageKey(dataSetId, storageName);
        Path target = resolveKey(storageKey);
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != fileSize) {
                return null;
            }
            return storedFile(storageName, storageKey, fileSize, sha256(target));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("检查已转存上传文件失败: " + originalName, ex);
        }
    }

    /** 数据库提交失败时把正式文件恢复成暂存文件，以便客户端原块重试。 */
    public void restoreCompletedFile(
            Long dataSetId, String taskNo, int fileIndex, String originalName) {
        Path target = resolveKey(dataSetStorageKey(
                dataSetId, uploadStorageName(taskNo, fileIndex, originalName)));
        Path source = uploadPartialPath(taskNo, fileIndex);
        try {
            if (!Files.exists(target)) {
                return;
            }
            Files.createDirectories(source.getParent());
            move(target, source);
        } catch (IOException ex) {
            throw new IllegalStateException("上传文件恢复暂存状态失败: " + originalName, ex);
        }
    }

    /** 清理指定文件尚未完成的暂存内容。 */
    public void deleteUploadPartial(String taskNo, int fileIndex) {
        deleteQuietly(uploadPartialPath(taskNo, fileIndex));
    }

    /** 校验磁盘当前可用空间是否覆盖新批次预留量与安全余量。 */
    public void ensureFreeSpace(long requiredBytes) {
        if (requiredBytes < 0) {
            throw new IllegalArgumentException("磁盘预留空间不能为负数");
        }
        Path stagingRoot = resolveKey(".staging");
        try {
            Files.createDirectories(stagingRoot);
            long usable = Files.getFileStore(stagingRoot).getUsableSpace();
            if (usable < requiredBytes) {
                throw new IllegalStateException(
                        "GIS上传存储空间不足，需要" + requiredBytes + "字节，可用" + usable + "字节");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查GIS上传存储空间", ex);
        }
    }

    public void deleteStaged(String stagingKey) {
        delete(resolveKey(stagingKey));
    }

    public void deleteStored(String storageKey) {
        delete(resolveKey(storageKey));
    }

    /** 删除数据集目录，但仅在目录为空时执行。 */
    public void deleteEmptyDataSetDirectory(Long dataSetId) {
        Path directory = resolveKey(dataSetDirectoryKey(dataSetId));
        if (!Files.exists(directory)) {
            return;
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("数据集存储路径不是目录: " + directory);
        }
        try {
            Files.delete(directory);
        } catch (DirectoryNotEmptyException ignored) {
            // 数据集仍有文件时保留目录。
        } catch (IOException ex) {
            throw new IllegalStateException("清理数据集空目录失败: " + directory, ex);
        }
    }

    /** 解析已入库的本地文件，并阻止存储根目录之外的路径穿越。 */
    public Path resolveStoredPath(String storageKey) {
        Path path = resolveKey(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("已入库文件不存在: " + storageKey);
        }
        return path;
    }

    public static void validateMultipartFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("单个文件不能超过500MB: " + file.getOriginalFilename());
        }
        extensionOf(file.getOriginalFilename());
    }

    public static String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 1 || index == fileName.length() - 1) {
            throw new IllegalArgumentException("文件扩展名不能为空: " + fileName);
        }
        String extension = fileName.substring(index + 1).toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,16}")) {
            throw new IllegalArgumentException("非法文件扩展名: " + extension);
        }
        return extension;
    }

    public static String safeOriginalName(String originalFilename) {
        String normalized = originalFilename.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("非法上传文件名: " + originalFilename);
        }
        return fileName;
    }

    private Path resolveKey(String key) {
        return pathResolver.resolveKey(key);
    }

    private String dataSetStorageKey(Long dataSetId, String storageName) {
        return dataSetDirectoryKey(dataSetId) + "/"
                + pathResolver.safeSegment(storageName);
    }

    private String dataSetDirectoryKey(Long dataSetId) {
        if (dataSetId == null || dataSetId <= 0) {
            throw new IllegalArgumentException("数据集ID必须大于0");
        }
        return "datasets/" + pathResolver.safeSegment(String.valueOf(dataSetId));
    }

    private Path uploadPartialPath(String taskNo, int fileIndex) {
        return resolveKey(".staging/" + pathResolver.safeSegment(taskNo)
                + "/" + fileIndex + ".part");
    }

    private String uploadStorageName(String taskNo, int fileIndex, String originalName) {
        String stableId = UUID.nameUUIDFromBytes(
                        (taskNo + ":" + fileIndex).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return stableId + "." + extensionOf(originalName);
    }

    private GisStoredFile storedFile(
            String storageName, String storageKey, long fileSize, String sha256) {
        return GisStoredFile.builder()
                .storageName(storageName)
                .storageKey(storageKey)
                .storageType("LOCAL")
                .sizeBytes(fileSize)
                .sha256(sha256)
                .build();
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private void readFully(FileChannel channel, byte[] target, int length, long position)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(target, 0, length);
        long current = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, current);
            if (read < 0) {
                throw new IOException("暂存文件重叠区读取不完整");
            }
            current += read;
        }
    }

    private void writeFully(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        long current = position;
        while (buffer.hasRemaining()) {
            current += channel.write(buffer, current);
        }
    }

    private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path);
             DigestInputStream digestInput = new DigestInputStream(input, digest)) {
            byte[] buffer = new byte[streamBufferSize];
            while (digestInput.read(buffer) != -1) {
                // DigestInputStream 在读取时完成摘要更新。
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void truncateQuietly(Path path, long size) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        } catch (IOException cleanupEx) {
            log.warn("回滚失败上传分块失败: path={}, size={}", path, size, cleanupEx);
        }
    }

    private String copyWithDigest(
            String taskNo,
            String originalName,
            MultipartFile file,
            Path target,
            LongConsumer progressListener) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = file.getInputStream();
             DigestInputStream digestInput = new DigestInputStream(input, digest);
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1024 * 1024];
            long copiedBytes = 0;
            int read;
            while ((read = digestInput.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copiedBytes += read;
                if (progressListener != null) {
                    try {
                        progressListener.accept(copiedBytes);
                    } catch (RuntimeException progressEx) {
                        log.debug("文件存储进度回报失败: taskNo={}, file={}, error={}",
                                taskNo, originalName, progressEx.getMessage());
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new IllegalStateException("删除文件失败: " + path, ex);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupEx) {
            log.warn("清理文件失败: {}", path, cleanupEx);
        }
    }
}
