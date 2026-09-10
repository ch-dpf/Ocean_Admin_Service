package org.ocean.admin.platform.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.platform.audit.entity.SysOperationLog;
import org.ocean.admin.platform.audit.mapper.SysOperationLogMapper;
import org.ocean.admin.platform.audit.utils.LogQuerySupport;
import org.ocean.admin.platform.audit.vo.LogQueryVO;
import org.ocean.admin.platform.audit.vo.SysOperationLogVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 系统操作日志服务
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysOperationLogService {

    private final SysOperationLogMapper operationLogMapper;

    /**
     * 分页查询操作日志
     */
    public PageResult<List<SysOperationLogVO>> queryOperationLogs(LogQueryVO query) {
        Page<SysOperationLog> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<SysOperationLog> wrapper = LogQuerySupport.buildCommonWrapper(
            query,
            SysOperationLog::getDeleted,
            SysOperationLog::getUsername,
            SysOperationLog::getStatus,
            SysOperationLog::getCreateTime
        );

        Page<SysOperationLog> resultPage = operationLogMapper.selectPage(page, wrapper);
        return LogQuerySupport.toPageResult(resultPage, this::convertToVO);
    }

    /**
     * 根据ID获取操作日志详情
     */
    public SysOperationLogVO getOperationLogById(Long id) {
        SysOperationLog entity = operationLogMapper.selectById(id);
        if (entity == null || entity.getDeleted() == 1) {
            return null;
        }
        return convertToVO(entity);
    }

    /**
     * 删除操作日志
     */
    @Transactional
    public boolean deleteOperationLog(Long id) {
        return operationLogMapper.deleteById(id) > 0;
    }

    /**
     * 批量删除操作日志
     */
    @Transactional
    public boolean batchDeleteOperationLogs(List<Long> ids) {
        return operationLogMapper.deleteBatchIds(ids) > 0;
    }

    /**
     * 清空操作日志
     */
    @Transactional
    public boolean clearOperationLogs() {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysOperationLog::getDeleted, 0);
        return operationLogMapper.delete(wrapper) >= 0;
    }

    /**
     * 记录操作日志
     */
    @Transactional
    public void recordOperationLog(SysOperationLog logEntity) {
        operationLogMapper.insert(logEntity);
    }

    /**
     * 实体转VO
     */
    private SysOperationLogVO convertToVO(SysOperationLog entity) {
        SysOperationLogVO vo = new SysOperationLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
