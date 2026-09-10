package org.ocean.admin.platform.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.platform.identity.entity.SysUserLockRecord;
import org.ocean.admin.platform.identity.mapper.SysUserLockRecordMapper;
import org.ocean.admin.platform.identity.vo.SysUserLockRecordVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SysUserLockRecordService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysUserLockRecordMapper lockRecordMapper;

    public PageResult<List<SysUserLockRecordVO>> queryLockRecords(String username, String recordType, Integer userId, String startTime, String endTime, Integer current, Integer size) {
        Page<SysUserLockRecord> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUserLockRecord> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isBlank()) {
            wrapper.like(SysUserLockRecord::getUsername, username);
        }
        if (recordType != null && !recordType.isBlank()) {
            wrapper.eq(SysUserLockRecord::getRecordType, recordType);
        }
        if (userId != null) {
            wrapper.eq(SysUserLockRecord::getUserId, userId);
        }
        if (startTime != null && !startTime.isBlank()) {
            wrapper.ge(SysUserLockRecord::getCreateTime, parseTime(startTime));
        }
        if (endTime != null && !endTime.isBlank()) {
            wrapper.le(SysUserLockRecord::getCreateTime, parseTime(endTime));
        }
        wrapper.orderByDesc(SysUserLockRecord::getCreateTime);
        Page<SysUserLockRecord> result = lockRecordMapper.selectPage(page, wrapper);
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords().stream().map(this::toVO).toList());
    }

    private LocalDateTime parseTime(String text) {
        return LocalDateTime.parse(text, TIME_FORMATTER);
    }

    private SysUserLockRecordVO toVO(SysUserLockRecord entity) {
        SysUserLockRecordVO vo = new SysUserLockRecordVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
