package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisUploadChunkCommand;
import org.ocean.admin.gis.service.FileUploadService;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** GIS 文件分块流式上传入口。 */
@Tag(name = "GIS文件上传", description = "GIS文件分块流式上传接口")
@RestController
@RequestMapping("/api/gis/uploads")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;

    @PostMapping(value = "/chunks", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "分块上传GIS文件",
            description = "通过gis.progress SSE订阅接收同一批次的上传与空间元数据解析进度；"
                    + "整批文件传输收口后才进入解析，stage表示当前阶段，activeFiles为正在解析的文件及步骤")
    @OperationLog(module = "GIS_FILE_UPLOAD", type = OperationType.SUBMIT,
            description = "分块流式上传GIS文件", recordResponse = true)
    public ResponseResult<Map<String, Object>> uploadChunk(
            @RequestHeader("Upload-Request-Id") String requestId,
            @RequestHeader("Upload-Data-Set-Id") Long dataSetId,
            @RequestHeader("Upload-File-Index") Integer fileIndex,
            @RequestHeader("Upload-File-Count") Integer fileCount,
            @RequestHeader("Upload-File-Name") String encodedFileName,
            @RequestHeader("Upload-File-Size") Long fileSize,
            @RequestHeader("Upload-Total-Size") Long totalSize,
            @RequestHeader("Upload-Offset") Long offset,
            @RequestHeader("Upload-Chunk-Size") Long chunkSize,
            @RequestHeader(value = "Upload-Vector-Placement", defaultValue = "AUTO") String vectorPlacement,
            HttpServletRequest request) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0 && contentLength != chunkSize) {
            throw new IllegalArgumentException(
                    "Content-Length必须与Upload-Chunk-Size一致");
        }
        String fileName = UriUtils.decode(encodedFileName, StandardCharsets.UTF_8);
        GisUploadChunkCommand command = new GisUploadChunkCommand(
                requestId,
                dataSetId,
                fileIndex,
                fileCount,
                fileName,
                fileSize,
                totalSize,
                offset,
                chunkSize,
                vectorPlacement);
        return ResponseResult.success(fileUploadService.uploadChunk(
                command, request.getInputStream()));
    }
}
