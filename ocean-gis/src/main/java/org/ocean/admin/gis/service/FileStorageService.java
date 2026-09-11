package org.ocean.admin.gis.service;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** 文件存储服务。 */
@Slf4j
@Service
public class FileStorageService {

    private static final long MAX_FILE_SIZE = 500L * 1024 * 1024;
    private final Path storageRoot;

    public FileStorageService(@Value("${gis.upload.base-path:uploads/gis}") String basePath) {
        this.storageRoot = Path.of(basePath).toAbsolutePath().normalize();
    }

    /** 在 HTTP 请求生命周期内将 MultipartFile 转存到受控临时目录。 */
    public GisStagedFile stage(String taskNo, MultipartFile file) {
        validateMultipartFile(file);
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String storageName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String stagingKey = ".staging/" + taskNo + "/" + storageName;
        Path target = resolveKey(stagingKey);

        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream();
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                Files.copy(digestInput, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return GisStagedFile.builder()
                    .originalName(originalName)
                    .storageName(storageName)
                    .stagingKey(stagingKey)
                    .extension(extension)
                    .sizeBytes(Files.size(target))
                    .sha256(HexFormat.of().formatHex(digest.digest()))
                    .contentType(file.getContentType())
                    .build();
        } catch (IOException | NoSuchAlgorithmException ex) {
            deleteQuietly(target);
            throw new IllegalStateException("文件暂存失败: " + originalName, ex);
        }
    }

    /** 将暂存文件移动到数据集正式目录。 */
    public GisStoredFile commit(String dataSetCode, String taskNo, GisStagedFile stagedFile) {
        String storageKey = "datasets/" + safeSegment(dataSetCode)
                + "/" + safeSegment(taskNo)
                + "/" + safeSegment(stagedFile.getStorageName());
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
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return GisStoredFile.builder()
                    .storageName(stagedFile.getStorageName())
                    .storageKey(storageKey)
                    .storageType("LOCAL")
                    .sizeBytes(stagedFile.getSizeBytes())
                    .sha256(stagedFile.getSha256())
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("文件转正失败: " + stagedFile.getOriginalName(), ex);
        }
    }

    public void deleteStaged(String stagingKey) {
        delete(resolveKey(stagingKey));
    }

    public void deleteStored(String storageKey) {
        delete(resolveKey(storageKey));
    }

    private void validateMultipartFile(MultipartFile file) {
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

    private String extensionOf(String fileName) {
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

    private String safeOriginalName(String originalFilename) {
        String normalized = originalFilename.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("非法上传文件名: " + originalFilename);
        }
        return fileName;
    }

    private Path resolveKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("存储Key不能为空");
        }
        Path resolved = storageRoot.resolve(key.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("非法存储Key: " + key);
        }
        return resolved;
    }

    private String safeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("非法存储路径片段: " + value);
        }
        return value;
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
