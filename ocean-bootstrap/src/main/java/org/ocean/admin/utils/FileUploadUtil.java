package org.ocean.admin.utils;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.config.FileUploadConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文件上传工具类
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUploadUtil {

    private final FileUploadConfig fileUploadConfig;

    private final UploadPathResolver uploadPathResolver;

    /**
     * 通用文件上传方法
     *
     * @param file           上传的文件
     * @param uploadPath     上传路径
     * @param allowedTypes   允许的文件类型
     * @param fileType       文件类型描述
     * @return 文件访问路径
     */
    private String uploadFile(MultipartFile file, String uploadPath,
                              String allowedTypes, String fileType) {
        return uploadFileDetail(file, uploadPath, allowedTypes, fileType).getUrl();
    }

    private UploadResult uploadFileDetail(MultipartFile file, String uploadPath,
                                          String allowedTypes, String fileType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        // 获取原始文件名
        String originalFilename = file.getOriginalFilename();
        if (StrUtil.isBlank(originalFilename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 获取文件扩展名
        String extension = FileUtil.extName(originalFilename);
        if (StrUtil.isBlank(extension)) {
            throw new IllegalArgumentException("文件扩展名不能为空");
        }

        // 验证文件类型
        if (StrUtil.isNotBlank(allowedTypes)) {
            List<String> allowedTypeList = Arrays.asList(allowedTypes.split(","));
            if (!allowedTypeList.contains(extension.toLowerCase())) {
                throw new IllegalArgumentException(
                        "不支持的" + fileType + "类型: " + extension);
            }
        }

        // 验证文件大小（500MB）
        if (file.getSize() > 500 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过500MB");
        }

        // 生成新的文件名：日期 + UUID + 扩展名
        String dateStr = DateUtil.format(DateUtil.date(), "yyyyMMdd");
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;

        try {
            File configuredDir = uploadPathResolver.resolveConfiguredPath(uploadPath, null);
            File destDir = new File(configuredDir, dateStr);
            if (!destDir.exists()) {
                destDir.mkdirs();
            }

            String filePath = new File(destDir, fileName).getCanonicalPath();

            // 保存文件
            File destFile = new File(filePath);
            file.transferTo(destFile);

            log.info("文件上传成功: {}", filePath);

            // 返回访问路径（相对于/static/）
            String relativePath = uploadPathResolver.resolveUploadsRoot()
                    .toPath()
                    .relativize(destFile.toPath())
                    .toString()
                    .replace('\\', '/');
            String url = "/static/" + relativePath;
            return new UploadResult(url, filePath, originalFilename, file.getSize());
        } catch (IOException e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadResult {
        private String url;
        private String absolutePath;
        private String originalFileName;
        private long fileSize;
    }

}
