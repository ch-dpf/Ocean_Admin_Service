package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.common.PageResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GisFileMetaServiceTest {

    @Test
    void pagesDeletedFileMetadataThroughCustomMapperQuery() {
        GisFileMetaMapper fileMetaMapper = mock(GisFileMetaMapper.class);
        GisDataSetMapper dataSetMapper = mock(GisDataSetMapper.class);
        GisFileMetaService service = new GisFileMetaService(
                fileMetaMapper, dataSetMapper, mock(GisDataSetService.class));
        GisFileMetaVO deletedMeta = new GisFileMetaVO();
        deletedMeta.setId(11L);
        deletedMeta.setDataSetId(22L);
        deletedMeta.setOriginalName("terrain.tif");

        when(fileMetaMapper.selectDeletedPage(
                any(), eq(22L), eq(1L), eq(33L), eq("terrain"), eq("tif"), eq("READY")))
                .thenAnswer(invocation -> {
                    Page<GisFileMetaVO> page = invocation.getArgument(0);
                    page.setRecords(List.of(deletedMeta));
                    page.setTotal(1);
                    return page;
                });
        when(dataSetMapper.selectList(any())).thenReturn(List.of());

        PageResult<List<GisFileMetaVO>> result = service.getDeletedFileMetaPage(
                2, 200, 22L, 1L, 33L, " terrain ", ".TIF", "ready");

        assertThat(result.getCurrent()).isEqualTo(2L);
        assertThat(result.getSize()).isEqualTo(100L);
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).extracting(GisFileMetaVO::getId).containsExactly(11L);
        verify(fileMetaMapper).selectDeletedPage(
                any(), eq(22L), eq(1L), eq(33L), eq("terrain"), eq("tif"), eq("READY"));
    }

    @Test
    void rejectsInvalidUploadStatusBeforeQueryingDeletedMetadata() {
        GisFileMetaMapper fileMetaMapper = mock(GisFileMetaMapper.class);
        GisFileMetaService service = new GisFileMetaService(
                fileMetaMapper, mock(GisDataSetMapper.class), mock(GisDataSetService.class));

        assertThatThrownBy(() -> service.getDeletedFileMetaPage(
                1, 10, null, null, null, null, null, "DELETED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("上传状态只能为 PENDING、READY 或 FAILED");
        verify(fileMetaMapper, never()).selectDeletedPage(
                any(), any(), any(), any(), any(), any(), any());
    }
}
