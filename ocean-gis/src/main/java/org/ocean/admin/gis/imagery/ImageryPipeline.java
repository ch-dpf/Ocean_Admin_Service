package org.ocean.admin.gis.imagery;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;

import java.nio.file.Path;
import java.util.function.Consumer;

/** 影像数据处理流程管道 */
@Slf4j
public class ImageryPipeline {

    private final GeoTiffPreprocessor preprocessor;
    private final GeoTiffTileGenerator tileGenerator;

    public ImageryPipeline(GeoTiffPreprocessor preprocessor,
            GeoTiffTileGenerator tileGenerator) {
        this.preprocessor = preprocessor;
        this.tileGenerator = tileGenerator;
    }

    public void execute(Path source, GisProcessingWorkspace workspace,
            ImageryTileOptions options,
            Consumer<TaskStage> progressListener) {
        long pipelineStartedAt = System.nanoTime();
        PipelinePhase phase = PipelinePhase.PREPROCESSING;
        boolean successful = false;
        log.info("[Imagery][Pipeline] 开始执行影像处理，源文件: {}, 输出目录: {}",
                source, workspace.outputPath());
        try {
            GeoTiffPreprocessor.PreparedRaster prepared = preprocessRaster(
                    source, options, progressListener);

            phase = PipelinePhase.TILING;
            runTilingProcess(prepared, source, workspace, options, progressListener);
            successful = true;
        } catch (RuntimeException ex) {
            log.error("[Imagery][Pipeline] 影像处理失败，阶段: {}, 源文件: {}",
                    phase.displayName, source, ex);
            throw ex;
        } finally {
            log.info("[Imagery][Pipeline] 影像处理流程结束，结果: {}, 最后阶段: {}, 总耗时: {} ms",
                    successful ? "成功" : "失败", phase.displayName,
                    elapsedMillis(pipelineStartedAt));
        }
    }

    private GeoTiffPreprocessor.PreparedRaster preprocessRaster(
            Path source, ImageryTileOptions options,
            Consumer<TaskStage> progressListener) {
        long startedAt = System.nanoTime();
        log.info("[Imagery][Pipeline] 开始预处理阶段，源文件: {}", source);
        GeoTiffPreprocessor.PreparedRaster prepared = preprocessor.prepare(
                source, options, progressListener);
        log.info("[Imagery][Pipeline] 预处理阶段完成，处理文件: {}, 已优化: {}, 命中缓存: {}, 耗时: {} ms",
                prepared.parts().getFirst().path(), prepared.optimized(), prepared.cacheHit(),
                elapsedMillis(startedAt));
        return prepared;
    }

    private void runTilingProcess(GeoTiffPreprocessor.PreparedRaster prepared,
            Path source, GisProcessingWorkspace workspace, ImageryTileOptions options,
            Consumer<TaskStage> progressListener) {
        long startedAt = System.nanoTime();
        log.info("[Imagery][Pipeline] 开始切片阶段，处理分块数: {}, 输出目录: {}",
                prepared.parts().size(), workspace.outputPath());
        tileGenerator.generate(prepared, source, workspace.outputPath(),
                options, progressListener);
        log.info("[Imagery][Pipeline] 切片阶段完成，输出目录: {}, 耗时: {} ms",
                workspace.outputPath(), elapsedMillis(startedAt));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private enum PipelinePhase {
        PREPROCESSING("预处理"),
        TILING("切片");

        private final String displayName;

        PipelinePhase(String displayName) {
            this.displayName = displayName;
        }
    }
}
