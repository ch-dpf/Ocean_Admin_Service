package org.ocean.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.entity.GisDataSet;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.mapper.GisDataSetMapper;
import org.ocean.admin.vo.GisDataSetVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * gis数据集服务
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GisDataSetService {

    private final GisDataSetMapper gisDataSetMapper;


    public PageResult<List<GisDataSet>> getDataSetPage(Integer current, Integer size) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        Page<GisDataSet> page = gisDataSetMapper.selectPage(
                new Page<>(currentPage, pageSize),
                new LambdaQueryWrapper<GisDataSet>()
                        .orderByDesc(GisDataSet::getCreateTime)
        );
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public GisDataSetVO createProject(GisDataSetVO reqVO) {
        // 校验数据集是否存在，不存在则新增，存在则返回错误原因提示
        validateDataSetRequest(reqVO, false);
        String dataSetName = reqVO.getDataSetName().trim();
        ensureDataSetNameAvailable(dataSetName, null);

        LocalDateTime now = LocalDateTime.now();
        GisDataSet dataSet = new GisDataSet();
        dataSet.setDataSetName(dataSetName);
        dataSet.setCategoryId(reqVO.getCategoryId());
        dataSet.setDataSetCode(generateDataSetCode());
        dataSet.setDescription(reqVO.getDescription());
        dataSet.setFileCount(0);
        dataSet.setCreateTime(now);
        dataSet.setUpdateTime(now);
        dataSet.setDeleted(0);

        if (gisDataSetMapper.insert(dataSet) != 1) {
            throw new IllegalStateException("数据集创建失败");
        }

        log.info("创建 GIS 数据集成功: id={}, code={}, name={}",
                dataSet.getId(), dataSet.getDataSetCode(), dataSet.getDataSetName());
        return toVO(dataSet);
    }

    private String generateDataSetCode() {
        return "GIS_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateProject(GisDataSetVO reqVO) {
        validateDataSetRequest(reqVO, true);

        GisDataSet existing = gisDataSetMapper.selectById(reqVO.getId());
        if (existing == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + reqVO.getId());
        }

        String dataSetName = reqVO.getDataSetName().trim();
        ensureDataSetNameAvailable(dataSetName, reqVO.getId());

        int updated = gisDataSetMapper.update(
                null,
                new LambdaUpdateWrapper<GisDataSet>()
                        .eq(GisDataSet::getId, reqVO.getId())
                        .set(GisDataSet::getDataSetName, dataSetName)
                        .set(GisDataSet::getCategoryId, reqVO.getCategoryId())
                        .set(GisDataSet::getDescription, reqVO.getDescription())
                        .set(GisDataSet::getUpdateTime, LocalDateTime.now())
        );
        if (updated != 1) {
            throw new IllegalStateException("数据集更新失败: " + reqVO.getId());
        }
        log.info("更新 GIS 数据集成功: id={}, name={}", reqVO.getId(), dataSetName);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (gisDataSetMapper.selectById(id) == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + id);
        }
        if (gisDataSetMapper.deleteById(id) != 1) {
            throw new IllegalStateException("数据集删除失败: " + id);
        }
        log.info("删除 GIS 数据集成功: id={}", id);
    }

    public GisDataSetVO getProjectDetail(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(id);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + id);
        }
        return toVO(dataSet);
    }

    private void validateDataSetRequest(GisDataSetVO reqVO, boolean requireId) {
        if (reqVO == null) {
            throw new IllegalArgumentException("数据集请求不能为空");
        }
        if (requireId && reqVO.getId() == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (reqVO.getDataSetName() == null || reqVO.getDataSetName().isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        if (reqVO.getCategoryId() == null) {
            throw new IllegalArgumentException("数据类别不能为空");
        }
        if (reqVO.getCategoryId() < 0 || reqVO.getCategoryId() > 2) {
            throw new IllegalArgumentException("数据类别只能为0-影像数据、1-地形数据、2-矢量数据");
        }
    }

    private void ensureDataSetNameAvailable(String dataSetName, Long excludedId) {
        LambdaQueryWrapper<GisDataSet> query = new LambdaQueryWrapper<GisDataSet>()
                .eq(GisDataSet::getDataSetName, dataSetName)
                .ne(excludedId != null, GisDataSet::getId, excludedId);
        if (gisDataSetMapper.exists(query)) {
            throw new IllegalArgumentException("数据集名称已存在: " + dataSetName);
        }
    }

    private GisDataSetVO toVO(GisDataSet dataSet) {
        GisDataSetVO result = new GisDataSetVO();
        result.setId(dataSet.getId());
        result.setDataSetName(dataSet.getDataSetName());
        result.setCategoryId(dataSet.getCategoryId());
        result.setDescription(dataSet.getDescription());
        return result;
    }
}
