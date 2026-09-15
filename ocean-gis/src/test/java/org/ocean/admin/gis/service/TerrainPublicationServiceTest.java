package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.entity.GisTerrainPublication;
import org.ocean.admin.gis.mapper.GisTerrainPublicationMapper;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.vo.GisTerrainPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class TerrainPublicationServiceTest {

    private final Path tempDir = Path.of("target", "test-work", "terrain-publication-service");

    @Test
    void pagesPublicationRecordsAndBuildsServiceUrls() {
        GisTerrainPublicationMapper publicationMapper = mock(GisTerrainPublicationMapper.class);
        TerrainPublicationService service = new TerrainPublicationService(
                publicationMapper, mock(GisTaskService.class), mock(GisProcessingStorageService.class));
        ReflectionTestUtils.setField(service, "publicBaseUrl", "http://localhost:8090/");
        GisTerrainPublication publication = new GisTerrainPublication();
        publication.setId(1L);
        publication.setServiceCode("TRN_0123456789ABCDEF0123");
        publication.setStatus("DISABLED");
        publication.setDataSetId(2L);
        publication.setPublishTime(LocalDateTime.of(2026, 9, 15, 10, 0));
        when(publicationMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<GisTerrainPublication> page = invocation.getArgument(0);
            page.setRecords(List.of(publication));
            page.setTotal(1);
            return page;
        });

        PageResult<List<GisTerrainPublicationVO>> result = service.getPublicationPage(
                0, 200, " trn_0123 ", " disabled ", 2L, null, null);

        assertThat(result.getCurrent()).isEqualTo(1L);
        assertThat(result.getSize()).isEqualTo(100L);
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).singleElement().satisfies(record -> {
            assertThat(record.getServiceCode()).isEqualTo("TRN_0123456789ABCDEF0123");
            assertThat(record.getServiceUrl())
                    .isEqualTo("http://localhost:8090/terrain/TRN_0123456789ABCDEF0123/");
        });
        verify(publicationMapper).selectPage(any(), any());
    }

    @Test
    void rejectsInvalidPublicationPageFilters() {
        GisTerrainPublicationMapper publicationMapper = mock(GisTerrainPublicationMapper.class);
        TerrainPublicationService service = new TerrainPublicationService(
                publicationMapper, mock(GisTaskService.class), mock(GisProcessingStorageService.class));

        assertThatThrownBy(() -> service.getPublicationPage(
                1, 10, null, "UNKNOWN", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("发布状态只能为 PUBLISHED 或 DISABLED");
        assertThatThrownBy(() -> service.getPublicationPage(
                1, 10, null, null, null,
                LocalDateTime.of(2026, 9, 16, 0, 0),
                LocalDateTime.of(2026, 9, 15, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("发布开始时间不能晚于发布结束时间");
        verify(publicationMapper, never()).selectPage(any(), any());
    }

    @Test
    void publishesCompletedTerrainTaskAndResolvesOnlyFilesInsideTilesRoot() throws Exception {
        GisTerrainPublicationMapper publicationMapper = mock(GisTerrainPublicationMapper.class);
        GisTaskService taskService = mock(GisTaskService.class);
        GisProcessingStorageService storageService = mock(GisProcessingStorageService.class);
        TerrainPublicationService service = new TerrainPublicationService(
                publicationMapper, taskService, storageService);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "http://localhost:8090/");

        Path tiles = Files.createDirectories(tempDir.resolve("tiles/0/0"));
        Files.writeString(tempDir.resolve("tiles/layer.json"), "{}");
        Files.write(tiles.resolve("0.terrain"), new byte[]{1, 2, 3});

        GisTask sourceTask = completedTerrainTask();
        when(taskService.getRequired(10L)).thenReturn(sourceTask);
        when(storageService.resolveTiles("terrain/20/TASK")).thenReturn(tempDir.resolve("tiles"));
        when(publicationMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            GisTask task = invocation.getArgument(0);
            task.setId(30L);
            return null;
        }).when(taskService).insert(any(GisTask.class));
        when(publicationMapper.insert(any(GisTerrainPublication.class))).thenAnswer(invocation -> {
            GisTerrainPublication publication = invocation.getArgument(0);
            publication.setId(40L);
            return 1;
        });

        GisTerrainPublicationVO result = service.publish(10L);

        assertThat(result.getId()).isEqualTo(40L);
        assertThat(result.getPublishTaskId()).isEqualTo(30L);
        assertThat(result.getServiceCode()).matches("TRN_[A-F0-9]{20}");
        assertThat(result.getServiceUrl())
                .isEqualTo("http://localhost:8090/terrain/" + result.getServiceCode() + "/");
        assertThat(service.resolvePublishedFile(result.getServiceCode(), "/layer.json"))
                .isEqualTo(tempDir.resolve("tiles/layer.json"));
        assertThatThrownBy(() -> service.resolvePublishedFile(
                result.getServiceCode(), "../../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("地形资源不存在");
        verify(taskService).insert(any(GisTask.class));
    }

    @Test
    void rejectsUnfinishedTerrainTask() {
        GisTerrainPublicationMapper publicationMapper = mock(GisTerrainPublicationMapper.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TerrainPublicationService service = new TerrainPublicationService(
                publicationMapper, taskService, mock(GisProcessingStorageService.class));
        GisTask task = completedTerrainTask();
        task.setTaskStatus("RUNNING");
        when(taskService.getRequired(10L)).thenReturn(task);

        assertThatThrownBy(() -> service.publish(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    private GisTask completedTerrainTask() {
        GisTask task = new GisTask();
        task.setId(10L);
        task.setTaskNo("GIS_TERRAIN_SOURCE");
        task.setTaskName("地形处理：terrain.tif");
        task.setTaskType(2L);
        task.setTaskStatus("COMPLETED");
        task.setProcessingType("TERRAIN");
        task.setOutputKey("terrain/20/TASK");
        task.setDataSetId(1L);
        task.setSourceFileMetaId(20L);
        return task;
    }
}
