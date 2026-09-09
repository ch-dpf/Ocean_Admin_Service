package org.ocean.admin.platform.identity.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.platform.identity.vo.LogQueryVO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 日志查询公共封装，避免多个日志服务重复构建查询条件与分页结果。
 */
public final class LogQuerySupport {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LogQuerySupport() {
    }

    public static <E> LambdaQueryWrapper<E> buildCommonWrapper(
            LogQueryVO query,
            SFunction<E, ?> deletedField,
            SFunction<E, ?> usernameField,
            SFunction<E, ?> statusField,
            SFunction<E, ?> timeField) {

        LambdaQueryWrapper<E> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(deletedField, 0);

        if (query.getUsername() != null && !query.getUsername().isEmpty()) {
            wrapper.like(usernameField, query.getUsername());
        }

        if (query.getStatus() != null) {
            wrapper.eq(statusField, query.getStatus());
        }

        if (query.getStartTime() != null && !query.getStartTime().isEmpty()) {
            wrapper.ge(timeField, parseTime(query.getStartTime()));
        }
        if (query.getEndTime() != null && !query.getEndTime().isEmpty()) {
            wrapper.le(timeField, parseTime(query.getEndTime()));
        }

        wrapper.orderByDesc(timeField);
        return wrapper;
    }

    public static <E, V> PageResult<List<V>> toPageResult(Page<E> page, Function<E, V> converter) {
        List<V> voList = page.getRecords().stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), voList);
    }

    private static LocalDateTime parseTime(String text) {
        return LocalDateTime.parse(text, TIME_FORMATTER);
    }
}

