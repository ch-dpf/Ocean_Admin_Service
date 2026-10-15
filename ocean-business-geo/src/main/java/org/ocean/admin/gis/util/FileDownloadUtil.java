package org.ocean.admin.gis.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Globals;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
     * <p>优先把文件范围交给 Tomcat sendfile；连接器不支持时才由应用流式写出。
     * sendfile 模式返回只代表传输已交给连接器，不代表客户端已经接收完成。</p>
     *
     * @param storageKey   上传根目录下的相对存储 Key
     * @param downloadName 客户端保存时使用的文件名，允许包含中文
     * @param request      下载请求，用于识别 Range、If-Range 与 HEAD
     * @param response     下载响应，方法内写入响应头与文件内容
     * @return 本次响应的内容长度及采用的传输模式
     */
    public DownloadResult writeToResponse(
            String storageKey,
            String downloadName,
            HttpServletRequest request,
            HttpServletResponse response) {
        return writePathToResponse(
                resolveStoredPath(storageKey), downloadName, request, response);
    }

    /** 把业务层已完成边界校验的本地文件写入下载响应。 */
    public DownloadResult writePathToResponse(
            Path source,
            String downloadName,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("待下载文件不存在");
        }
        source = source.toAbsolutePath().normalize();
        String fileName = safeFileName(downloadName);
        try {
            long sizeBytes = Files.size(source);
            long lastModifiedMillis = Files.getLastModifiedTime(source).toMillis();
            String etag = etagOf(sizeBytes, lastModifiedMillis);

            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.ETAG, etag);
            response.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModifiedMillis);

            ByteRange range = null;
            String rangeHeader = request.getHeader(HttpHeaders.RANGE);
            if (rangeHeader != null && !rangeHeader.isBlank()
                    && ifRangeMatches(request.getHeader(HttpHeaders.IF_RANGE), etag,
                    lastModifiedMillis)) {
                try {
                    range = parseRange(rangeHeader, sizeBytes);
                } catch (IllegalArgumentException invalidRange) {
                    response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + sizeBytes);
                    response.setContentLengthLong(0);
                    return new DownloadResult(0, TransferMode.NONE);
                }
            }

            response.setContentType(contentTypeOf(fileName));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName));

            long start = 0;
            long contentLength = sizeBytes;
            if (range != null) {
                start = range.start();
                contentLength = range.length();
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader(HttpHeaders.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + range.end() + "/" + sizeBytes);
            }
            response.setContentLengthLong(contentLength);

            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                return new DownloadResult(contentLength, TransferMode.NONE);
            }
            if (contentLength > 0 && supportsTomcatSendfile(request)) {
                delegateToTomcatSendfile(request, source, start, contentLength);
                return new DownloadResult(contentLength, TransferMode.TOMCAT_SENDFILE);
            }
            copyRange(source, start, contentLength, response.getOutputStream());
            return new DownloadResult(contentLength, TransferMode.APPLICATION_STREAM);
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

    private static ByteRange parseRange(String header, long fileSize) {
        String value = header.trim();
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0 || fileSize <= 0) {
            throw new IllegalArgumentException("仅支持单区间字节范围");
        }
        String specification = value.substring("bytes=".length()).trim();
        int separator = specification.indexOf('-');
        if (separator < 0 || separator != specification.lastIndexOf('-')) {
            throw new IllegalArgumentException("字节范围格式不合法");
        }

        String startValue = specification.substring(0, separator).trim();
        String endValue = specification.substring(separator + 1).trim();
        try {
            if (startValue.isEmpty()) {
                long suffixLength = Long.parseLong(endValue);
                if (suffixLength <= 0) {
                    throw new IllegalArgumentException("后缀字节数必须大于0");
                }
                long start = Math.max(0, fileSize - suffixLength);
                return new ByteRange(start, fileSize - 1);
            }

            long start = Long.parseLong(startValue);
            if (start < 0 || start >= fileSize) {
                throw new IllegalArgumentException("起始字节超出文件范围");
            }
            long end = endValue.isEmpty() ? fileSize - 1 : Long.parseLong(endValue);
            if (end < start) {
                throw new IllegalArgumentException("结束字节不能小于起始字节");
            }
            return new ByteRange(start, Math.min(end, fileSize - 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("字节范围必须为整数", ex);
        }
    }

    private static boolean ifRangeMatches(
            String ifRange,
            String etag,
            long lastModifiedMillis) {
        if (ifRange == null || ifRange.isBlank()) {
            return true;
        }
        String value = ifRange.trim();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return value.equals(etag);
        }
        try {
            long ifRangeMillis = ZonedDateTime.parse(
                    value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return lastModifiedMillis / 1000 <= ifRangeMillis / 1000;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static String etagOf(long sizeBytes, long lastModifiedMillis) {
        return "\"" + Long.toHexString(sizeBytes) + "-"
                + Long.toHexString(lastModifiedMillis) + "\"";
    }

    private static boolean supportsTomcatSendfile(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(Globals.SENDFILE_SUPPORTED_ATTR));
    }

    private static void delegateToTomcatSendfile(
            HttpServletRequest request,
            Path source,
            long start,
            long contentLength) throws IOException {
        request.setAttribute(Globals.SENDFILE_FILENAME_ATTR, source.toRealPath().toString());
        request.setAttribute(Globals.SENDFILE_FILE_START_ATTR, start);
        request.setAttribute(Globals.SENDFILE_FILE_END_ATTR, start + contentLength);
    }

    private static void copyRange(
            Path source,
            long start,
            long length,
            OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        long remaining = length;
        try (SeekableByteChannel channel = Files.newByteChannel(source, StandardOpenOption.READ)) {
            channel.position(start);
            while (remaining > 0) {
                byteBuffer.clear();
                byteBuffer.limit((int) Math.min(buffer.length, remaining));
                int read = channel.read(byteBuffer);
                if (read < 0) {
                    throw new IOException("文件在下载过程中被截断");
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        output.flush();
    }

    public record DownloadResult(long contentLength, TransferMode transferMode) {
        public boolean transferAccepted() {
            return transferMode != TransferMode.NONE;
        }
    }

    public enum TransferMode {
        NONE,
        TOMCAT_SENDFILE,
        APPLICATION_STREAM
    }

    private record ByteRange(long start, long end) {
        private long length() {
            return end - start + 1;
        }
    }
}
