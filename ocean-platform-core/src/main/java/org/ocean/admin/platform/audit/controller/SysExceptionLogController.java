package org.ocean.admin.platform.audit.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.audit.service.SysExceptionLogService;
import org.ocean.admin.platform.audit.utils.LogResponseSupport;
import org.ocean.admin.platform.audit.vo.LogQueryVO;
import org.ocean.admin.platform.audit.vo.SysExceptionLogVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统异常日志控制器
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@RestController
@RequestMapping("/api/log/exception")
@RequiredArgsConstructor
@Tag(name = "系统异常日志", description = "异常日志管理接口")
public class SysExceptionLogController {

    private final SysExceptionLogService exceptionLogService;

    /**
     * 分页查询异常日志
     */
    @GetMapping("/query")
    @Operation(summary = "分页查询异常日志")
    public ResponseResult<PageResult<List<SysExceptionLogVO>>> queryExceptionLogs(LogQueryVO query) {
        PageResult<List<SysExceptionLogVO>> result = exceptionLogService.queryExceptionLogs(query);
        return ResponseResult.success(result);
    }

    /**
     * 根据ID获取异常日志详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取异常日志详情")
    public ResponseResult<SysExceptionLogVO> getExceptionLogById(@PathVariable Long id) {
        SysExceptionLogVO vo = exceptionLogService.getExceptionLogById(id);
        return LogResponseSupport.successOrNotFound(vo, "异常日志不存在");
    }

    /**
     * 删除异常日志
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除异常日志")
    public ResponseResult<Boolean> deleteExceptionLog(@PathVariable Long id) {
        boolean success = exceptionLogService.deleteExceptionLog(id);
        return LogResponseSupport.booleanResult(success, "删除失败");
    }

    /**
     * 批量删除异常日志
     */
    @DeleteMapping("/batch-delete")
    @Operation(summary = "批量删除异常日志")
    public ResponseResult<Boolean> batchDeleteExceptionLogs(@RequestBody List<Long> ids) {
        boolean success = exceptionLogService.batchDeleteExceptionLogs(ids);
        return LogResponseSupport.booleanResult(success, "批量删除失败");
    }

    /**
     * 清空异常日志
     */
    @DeleteMapping("/clear")
    @Operation(summary = "清空异常日志")
    public ResponseResult<Boolean> clearExceptionLogs() {
        boolean success = exceptionLogService.clearExceptionLogs();
        return LogResponseSupport.booleanResult(success, "清空失败");
    }

    /**
     * 标记为已处理
     */
    @PutMapping("/handle/{id}")
    @Operation(summary = "标记异常为已处理")
    public ResponseResult<Boolean> markAsHandled(
            @PathVariable Long id,
            @RequestBody HandleRequest request) {
        boolean success = exceptionLogService.markAsHandled(
            id, 
            request.getHandleRemark(), 
            request.getHandleUser()
        );
        return LogResponseSupport.booleanResult(success, "标记失败");
    }

    @Data
    static class HandleRequest {
        private String handleRemark;
        private String handleUser;
    }
}
