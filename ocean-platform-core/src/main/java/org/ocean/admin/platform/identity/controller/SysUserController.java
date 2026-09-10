package org.ocean.admin.platform.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.identity.entity.SysUser;
import org.ocean.admin.platform.identity.service.SysUserLockRecordService;
import org.ocean.admin.platform.identity.service.SysUserService;
import org.ocean.admin.platform.identity.vo.SysUserLockRecordVO;
import org.ocean.admin.platform.identity.vo.UserOnlineDeviceVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/member/user")
@RequiredArgsConstructor
@Tag(name = "系统用户管理", description = "用户管理接口")
public class SysUserController {

    private final SysUserService userService;
    private final SysUserLockRecordService lockRecordService;

    @GetMapping("/query")
    @Operation(summary = "分页查询用户")
    public ResponseResult<PageResult<List<SysUser>>> queryUsers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        PageResult<List<SysUser>> result = userService.queryUsers(username, status, current, size);
        return ResponseResult.success(result);
    }

    @GetMapping("/{id}/online-devices")
    @Operation(summary = "查询用户当前在线设备")
    public ResponseResult<List<UserOnlineDeviceVO>> listOnlineDevices(@PathVariable Long id) {
        return ResponseResult.success(userService.listOnlineDevices(id));
    }

    @PostMapping("/{id}/online-devices/{sessionId}/kickout")
    @Operation(summary = "踢出用户指定在线设备")
    public ResponseResult<Boolean> kickoutOnlineDevice(@PathVariable Long id, @PathVariable String sessionId) {
        boolean success = userService.kickoutOnlineDevice(id, sessionId);
        return success ? ResponseResult.success(true) : ResponseResult.error("踢出失败");
    }

    @PostMapping("/create")
    @Operation(summary = "创建用户")
    public ResponseResult<Boolean> createUser(@RequestBody SysUser user) {
        boolean success = userService.createUser(user);
        return success ? ResponseResult.success(true) : ResponseResult.error("创建失败");
    }

    @PutMapping("/update")
    @Operation(summary = "更新用户")
    public ResponseResult<Boolean> updateUser(@RequestBody SysUser user) {
        boolean success = userService.updateUser(user);
        return success ? ResponseResult.success(true) : ResponseResult.error("更新失败");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public ResponseResult<Boolean> deleteUser(@PathVariable Long id) {
        boolean success = userService.deleteUser(id);
        return success ? ResponseResult.success(true) : ResponseResult.error("删除失败");
    }

    @PutMapping("/reset-password/{id}")
    @Operation(summary = "重置密码")
    public ResponseResult<Boolean> resetPassword(@PathVariable Long id, @RequestBody PasswordRequest request) {
        boolean success = userService.resetPassword(id, request.getNewPassword());
        return success ? ResponseResult.success(true) : ResponseResult.error("重置失败");
    }

    @PutMapping("/unlock/{id}")
    @Operation(summary = "手动解锁用户")
    public ResponseResult<Boolean> unlockUser(@PathVariable Long id, @RequestBody(required = false) UnlockRequest request) {
        String operatorName = request == null ? null : request.getOperatorName();
        boolean success = userService.unlockUser(id, operatorName);
        return success ? ResponseResult.success(true) : ResponseResult.error("解锁失败");
    }

    @GetMapping("/lock-record/query")
    @Operation(summary = "分页查询用户锁定记录")
    public ResponseResult<PageResult<List<SysUserLockRecordVO>>> queryLockRecords(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return ResponseResult.success(lockRecordService.queryLockRecords(username, recordType, userId, startTime, endTime, current, size));
    }

    @Data
    static class PasswordRequest {
        private String newPassword;
    }

    @Data
    static class UnlockRequest {
        private String operatorName;
    }
}
