package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.UnifiedPublicationRequest;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationService;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/** GIS 统一多协议发布管理入口。 */
@RestController
@RequestMapping("/api/gis/publications")
@Tag(name = "GIS服务发布", description = "按已完成任务发布影像、地形和矢量多协议服务")
@RequiredArgsConstructor
public class PublicationController {

    private final PublicationService publicationService;

    /** 发布和重命名共用的服务名称请求。 */
    public record ServiceNameRequest(@NotBlank @Size(max = 200) String serviceName) {
        public ServiceNameRequest {
            if (serviceName != null) {
                serviceName = serviceName.strip();
            }
        }
    }

    @PutMapping("/{processingType}/{serviceCode}/name")
    @Operation(summary = "重命名GIS服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.UPDATE,
            description = "重命名GIS服务", recordResponse = true)
    public ResponseResult<GisPublicationVO> rename(
            @PathVariable GisProcessingType processingType,
            @PathVariable String serviceCode,
            @Valid @RequestBody ServiceNameRequest request) {
        return ResponseResult.success("服务名称已更新",
                publicationService.rename(processingType, serviceCode, request.serviceName()));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询GIS发布记录")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询GIS发布记录", recordResponse = true)
    public ResponseResult<PageResult<List<GisPublicationVO>>> getPublicationPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
            @RequestParam(required = false) String processingType,
            @RequestParam(required = false) String serviceCode,
            @Parameter(description = "发布状态：PUBLISHED、DISABLED")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dataSetId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime publishTimeStart,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime publishTimeEnd) {
        return ResponseResult.success(publicationService.getPublicationPage(
                current, size, processingType, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd));
    }

    @PostMapping("/{processingType}/{sourceTaskId}/publish")
    @Operation(summary = "按所选协议集合发布已完成的任务产物")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布统一多协议 GIS 服务", recordResponse = true)
    public ResponseResult<GisPublicationVO> publish(
            @Parameter(description = "发布类型：TERRAIN、IMAGERY 或 VECTOR")
            @PathVariable GisProcessingType processingType,
            @PathVariable Long sourceTaskId,
            @Valid @RequestBody UnifiedPublicationRequest request) throws IOException {
        return ResponseResult.success("GIS服务发布成功",
                publicationService.publish(processingType, sourceTaskId, request));
    }

    @GetMapping("/{processingType}/{serviceCode}")
    @Operation(summary = "查询GIS发布信息")
    public ResponseResult<GisPublicationVO> get(
            @PathVariable GisProcessingType processingType,
            @PathVariable String serviceCode) {
        return ResponseResult.success(publicationService.get(processingType, serviceCode));
    }

    @PutMapping("/{processingType}/{serviceCode}/disable")
    @Operation(summary = "停用GIS服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.CANCEL,
            description = "停用GIS服务", recordResponse = true)
    public ResponseResult<GisPublicationVO> disable(
            @PathVariable GisProcessingType processingType,
            @PathVariable String serviceCode) {
        return ResponseResult.success("GIS服务已停用",
                publicationService.disable(processingType, serviceCode));
    }
}
