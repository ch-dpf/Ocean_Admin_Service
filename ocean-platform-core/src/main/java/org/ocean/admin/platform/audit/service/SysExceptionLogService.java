package org.ocean.admin.platform.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.platform.audit.entity.SysExceptionLog;
import org.ocean.admin.platform.audit.mapper.SysExceptionLogMapper;
import org.ocean.admin.platform.audit.utils.LogQuerySupport;
import org.ocean.admin.platform.audit.vo.LogQueryVO;
import org.ocean.admin.platform.identity.vo.SysExceptionLogVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统异常日志服务
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysExceptionLogService {

    private final SysExceptionLogMapper exceptionLogMapper;

    /**
     * 分页查询异常日志
     */
    public PageResult<List<SysExceptionLogVO>> queryExceptionLogs(LogQueryVO query) {
        Page<SysExceptionLog> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<SysExceptionLog> wrapper = LogQuerySupport.buildCommonWrapper(
            query,
            SysExceptionLog::getDeleted,
            SysExceptionLog::getUsername,
            SysExceptionLog::getStatus,
            SysExceptionLog::getCreateTime
        );

        Page<SysExceptionLog> resultPage = exceptionLogMapper.selectPage(page, wrapper);
        return LogQuerySupport.toPageResult(resultPage, this::convertToVO);
    }

    /**
     * 根据ID获取异常日志详情
     */
    public SysExceptionLogVO getExceptionLogById(Long id) {
        SysExceptionLog entity = exceptionLogMapper.selectById(id);
        if (entity == null || entity.getDeleted() == 1) {
            return null;
        }
        return convertToVO(entity);
    }

    /**
     * 删除异常日志
     */
    @Transactional
    public boolean deleteExceptionLog(Long id) {
        return exceptionLogMapper.deleteById(id) > 0;
    }

    /**
     * 批量删除异常日志
     */
    @Transactional
    public boolean batchDeleteExceptionLogs(List<Long> ids) {
        return exceptionLogMapper.deleteBatchIds(ids) > 0;
    }

    /**
     * 清空异常日志
     */
    @Transactional
    public boolean clearExceptionLogs() {
        LambdaQueryWrapper<SysExceptionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysExceptionLog::getDeleted, 0);
        return exceptionLogMapper.delete(wrapper) >= 0;
    }

    /**
     * 标记为已处理
     */
    @Transactional
    public boolean markAsHandled(Long id, String handleRemark, String handleUser) {
        LambdaUpdateWrapper<SysExceptionLog> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysExceptionLog::getId, id);
        wrapper.set(SysExceptionLog::getStatus, 1);
        wrapper.set(SysExceptionLog::getHandleRemark, handleRemark);
        wrapper.set(SysExceptionLog::getHandleTime, LocalDateTime.now());
        wrapper.set(SysExceptionLog::getHandleUser, handleUser);
        
        return exceptionLogMapper.update(null, wrapper) > 0;
    }

    /**
     * 记录异常日志
     */
    @Transactional
    public void recordExceptionLog(SysExceptionLog logEntity) {
        exceptionLogMapper.insert(logEntity);
    }

    /**
     * 实体转VO
     */
    private SysExceptionLogVO convertToVO(SysExceptionLog entity) {
        SysExceptionLogVO vo = new SysExceptionLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
