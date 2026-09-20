package org.ocean.admin.gis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件下载工具类：下载响应头组装、文件名编码与内容流式写出。 */
class FileDownloadUtilTest {

    private static final String STORAGE_KEY = "datasets/DS_1/TASK_1/report.tif";

    /** 上传根目录，与 UploadPathResolver 的约束保持同一份配置。 */
    @TempDir
    Path storageRoot;

    @Test
    void writeToResponseStreamsStoredFileWithAttachmentHeaders() throws IOException {
        byte[] content = "gis-file-content".getBytes(StandardCharsets.UTF_8);
        writeStoredFile(content);

        MockHttpServletResponse response = new MockHttpServletResponse();
        long written = downloadUtil().writeToResponse(STORAGE_KEY, "report.tif", response);

        assertEquals(content.length, written);
        assertEquals("image/tiff", response.getContentType());
        assertEquals(content.length, response.getContentLength());
        assertEquals("attachment; filename=\"report.tif\"; filename*=UTF-8''report.tif",
                response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(content, response.getContentAsByteArray());
    }

    @Test
    void writeToResponseEncodesNonAsciiFileName() throws IOException {
        writeStoredFile(new byte[]{1, 2, 3});

        MockHttpServletResponse response = new MockHttpServletResponse();
        downloadUtil().writeToResponse(STORAGE_KEY, "地形数据.tif", response);

        assertEquals(
                "attachment; filename=\"____.tif\"; filename*=UTF-8''"
                        + "%E5%9C%B0%E5%BD%A2%E6%95%B0%E6%8D%AE.tif",
                response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void writeToResponseStripsHeaderInjectionCharacters() throws IOException {
        writeStoredFile(new byte[]{9});

        MockHttpServletResponse response = new MockHttpServletResponse();
        downloadUtil().writeToResponse(STORAGE_KEY, "../evil\"\r\nX-Injected: 1.tif", response);

        String header = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(header);
        assertTrue(header.startsWith("attachment; filename=\"evil"));
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
    }

    @Test
    void writeToResponseRejectsKeyOutsideStorageRoot() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class, () -> downloadUtil()
                .writeToResponse("../escape.tif", "escape.tif", response));
        assertFalse(response.isCommitted());
    }

    @Test
    void writeToResponseRejectsMissingStoredFile() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class, () -> downloadUtil()
                .writeToResponse("datasets/DS_1/TASK_1/missing.tif", "missing.tif", response));
        assertFalse(response.isCommitted());
    }

    private FileDownloadUtil downloadUtil() {
        FileUploadConfig config = new FileUploadConfig();
        config.setBasePath(storageRoot.toString());
        return new FileDownloadUtil(new UploadPathResolver(config));
    }

    private void writeStoredFile(byte[] content) throws IOException {
        Path storedFile = Paths.get(storageRoot.toString(), STORAGE_KEY.split("/"));
        Files.createDirectories(storedFile.getParent());
        Files.write(storedFile, content);
    }
}
