package org.ocean.admin.platform.audit.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.audit.service.SysOperationLogService;
import org.ocean.admin.platform.audit.utils.LogResponseSupport;
import org.ocean.admin.platform.audit.vo.LogQueryVO;
import org.ocean.admin.platform.audit.vo.SysOperationLogVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统操作日志控制器
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@RestController
@RequestMapping("/api/log/operation")
@RequiredArgsConstructor
@Tag(name = "系统操作日志", description = "操作日志管理接口")
public class SysOperationLogController {

    private final SysOperationLogService operationLogService;

    /**
     * 分页查询操作日志
     */
    @GetMapping("/query")
    @Operation(summary = "分页查询操作日志")
    public ResponseResult<PageResult<List<SysOperationLogVO>>> queryOperationLogs(LogQueryVO query) {
        PageResult<List<SysOperationLogVO>> result = operationLogService.queryOperationLogs(query);
        return ResponseResult.success(result);
    }

    /**
     * 根据ID获取操作日志详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取操作日志详情")
    public ResponseResult<SysOperationLogVO> getOperationLogById(@PathVariable Long id) {
        SysOperationLogVO vo = operationLogService.getOperationLogById(id);
        return LogResponseSupport.successOrNotFound(vo, "操作日志不存在");
    }

    /**
     * 删除操作日志
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除操作日志")
    public ResponseResult<Boolean> deleteOperationLog(@PathVariable Long id) {
        boolean success = operationLogService.deleteOperationLog(id);
        return LogResponseSupport.booleanResult(success, "删除失败");
    }

    /**
     * 批量删除操作日志
     */
    @DeleteMapping("/batch-delete")
    @Operation(summary = "批量删除操作日志")
    public ResponseResult<Boolean> batchDeleteOperationLogs(@RequestBody List<Long> ids) {
        boolean success = operationLogService.batchDeleteOperationLogs(ids);
        return LogResponseSupport.booleanResult(success, "批量删除失败");
    }

    /**
     * 清空操作日志
     */
    @DeleteMapping("/clear")
    @Operation(summary = "清空操作日志")
    public ResponseResult<Boolean> clearOperationLogs() {
        boolean success = operationLogService.clearOperationLogs();
        return LogResponseSupport.booleanResult(success, "清空失败");
    }
}
