package org.ocean.admin.gis.util;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** GIS 已入库文件的下载响应组装与内容流式写出。 */
@Component
public class FileDownloadUtil {

    /** 单次读写缓冲区；按块转发，避免大文件整体载入内存。 */
    private static final int BUFFER_SIZE = 1024 * 1024;

    /** 无法从扩展名识别时统一按二进制流下载。 */
    private static final String DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    /** 常见扩展名对应的响应类型，供浏览器保存或预览时识别文件。 */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("json", "application/json"),
            Map.entry("geojson", "application/geo+json"),
            Map.entry("xml", "application/xml"),
            Map.entry("txt", "text/plain"));

    private final UploadPathResolver pathResolver;

    public FileDownloadUtil(UploadPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * 按存储 Key 把已入库文件写入下载响应。
     *
     * <p>响应头与文件内容都直接写到响应流，方法返回即代表响应已提交，
     * 调用方只能在返回之后再登记下载记录。</p>
     *
     * @param storageKey   上传根目录下的相对存储 Key
     * @param downloadName 客户端保存时使用的文件名，允许包含中文
     * @param response     下载响应，方法内写入响应头与文件内容
     * @return 实际写出的字节数
     */
    public long writeToResponse(
            String storageKey,
            String downloadName,
            HttpServletResponse response) {
        Path source = resolveStoredPath(storageKey);
        String fileName = safeFileName(downloadName);
        try {
            long sizeBytes = Files.size(source);
            response.setContentType(contentTypeOf(fileName));
            response.setContentLengthLong(sizeBytes);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName));
            try (InputStream input = Files.newInputStream(source)) {
                copy(input, response.getOutputStream());
            }
            return sizeBytes;
        } catch (IOException ex) {
            throw new IllegalStateException("文件下载写出失败: " + fileName, ex);
        }
    }

    /** 解析已入库的本地文件；越出上传根目录的 Key 由 UploadPathResolver 拒绝。 */
    private Path resolveStoredPath(String storageKey) {
        Path path = pathResolver.resolveKey(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("已入库文件不存在: " + storageKey);
        }
        return path;
    }

    /** 清洗文件名：去掉路径片段，并把引号与控制字符替换掉，避免响应头注入。 */
    private static String safeFileName(String downloadName) {
        String name = downloadName == null ? "" : downloadName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            cleaned.append(ch < 0x20 || ch == 0x7F || ch == '"' ? '_' : ch);
        }
        return cleaned.isEmpty() ? "download" : cleaned.toString();
    }

    /**
     * 组装附件响应头：同时给出 ASCII 回退名与 RFC 5987 编码名，
     * 保证非 ASCII 文件名在各浏览器下都能正确保存。
     */
    private static String contentDisposition(String fileName) {
        StringBuilder asciiName = new StringBuilder(fileName.length());
        for (int index = 0; index < fileName.length(); index++) {
            char ch = fileName.charAt(index);
            asciiName.append(ch < 0x80 ? ch : '_');
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName;
    }

    private static String contentTypeOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return DEFAULT_CONTENT_TYPE;
        }
        return CONTENT_TYPES.getOrDefault(
                fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT),
                DEFAULT_CONTENT_TYPE);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }
}
