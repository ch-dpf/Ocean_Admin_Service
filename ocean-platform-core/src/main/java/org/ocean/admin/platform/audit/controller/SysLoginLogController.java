package org.ocean.admin.platform.audit.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.audit.service.SysLoginLogService;
import org.ocean.admin.platform.audit.utils.LogResponseSupport;
import org.ocean.admin.platform.audit.vo.LogQueryVO;
import org.ocean.admin.platform.audit.vo.SysLoginLogVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统登录日志控制器
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@RestController
@RequestMapping("/api/log/login")
@RequiredArgsConstructor
@Tag(name = "系统登录日志", description = "登录日志管理接口")
public class SysLoginLogController {

    private final SysLoginLogService loginLogService;

    /**
     * 分页查询登录日志
     */
    @GetMapping("/query")
    @Operation(summary = "分页查询登录日志")
    public ResponseResult<PageResult<List<SysLoginLogVO>>> queryLoginLogs(LogQueryVO query) {
        PageResult<List<SysLoginLogVO>> result = loginLogService.queryLoginLogs(query);
        return ResponseResult.success(result);
    }

    /**
     * 根据ID获取登录日志详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取登录日志详情")
    public ResponseResult<SysLoginLogVO> getLoginLogById(@PathVariable Long id) {
        SysLoginLogVO vo = loginLogService.getLoginLogById(id);
        return LogResponseSupport.successOrNotFound(vo, "登录日志不存在");
    }

    /**
     * 删除登录日志
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除登录日志")
    public ResponseResult<Boolean> deleteLoginLog(@PathVariable Long id) {
        boolean success = loginLogService.deleteLoginLog(id);
        return LogResponseSupport.booleanResult(success, "删除失败");
    }

    /**
     * 批量删除登录日志
     */
    @DeleteMapping("/batch-delete")
    @Operation(summary = "批量删除登录日志")
    public ResponseResult<Boolean> batchDeleteLoginLogs(@RequestBody List<Long> ids) {
        boolean success = loginLogService.batchDeleteLoginLogs(ids);
        return LogResponseSupport.booleanResult(success, "批量删除失败");
    }

    /**
     * 清空登录日志
     */
    @DeleteMapping("/clear")
    @Operation(summary = "清空登录日志")
    public ResponseResult<Boolean> clearLoginLogs() {
        boolean success = loginLogService.clearLoginLogs();
        return LogResponseSupport.booleanResult(success, "清空失败");
    }
}
