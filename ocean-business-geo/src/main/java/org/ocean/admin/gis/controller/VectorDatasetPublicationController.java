package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.service.VectorDatasetPublicationService;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 数据管理中的数据集 WMS 发布入口，与任务成果发布分开。 */
@RestController
@RequestMapping("/api/gis/publications/VECTOR")
@RequiredArgsConstructor
@Tag(name = "GIS数据集WMS发布")
public class VectorDatasetPublicationController {
    private final VectorDatasetPublicationService publications;

    @PostMapping("/wms/publish")
    @Operation(summary = "直接发布矢量数据集动态WMS")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.SUBMIT, description = "发布数据集WMS")
    public ResponseResult<GisPublicationVO> publish(@Valid @RequestBody GisVectorRequests.Publish request) throws IOException {
        return ResponseResult.success(publications.publish(request));
    }

    @PutMapping("/{serviceCode}/styles")
    @Operation(summary = "更新数据集WMS样式，保持已发布数据版本")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.UPDATE, description = "更新数据集WMS样式")
    public ResponseResult<GisPublicationVO> styles(@PathVariable String serviceCode,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody GisVectorRequests.Publish request) {
        return ResponseResult.success(publications.updateStyles(serviceCode, ifMatch, request));
    }
}
