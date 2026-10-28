package org.ocean.admin.gis.imagery;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    public void execute(List<Path> sources, GisProcessingWorkspace workspace,
            ImageryTileOptions options,
            Consumer<ProgressStage> progressListener) {
        long pipelineStartedAt = System.nanoTime();
        PipelinePhase phase = PipelinePhase.PREPROCESSING;
        boolean successful = false;
        log.info("[Imagery][Pipeline] 开始执行影像处理，源文件: {}, 输出目录: {}",
                sources, workspace.outputPath());
        try {
            progressListener.accept(ProgressStage.indeterminate("analyzing", "正在规划影像集合范围和瓦片预算"));
            GeoTiffTileGenerator.MosaicPlan plan = tileGenerator.plan(sources, options);
            List<GeoTiffPreprocessor.PreparedRaster> prepared = new ArrayList<>();
            for (GeoTiffTileGenerator.SourceRaster source : plan.sources()) {
                source.validateUnchanged();
                prepared.add(preprocessRaster(source.path(), source.nativeZoom(), options,
                        stage -> progressListener.accept(new ProgressStage(stage.code(),
                                "源文件" + (source.sequence() + 1) + "/" + sources.size()
                                        + "：" + stage.message(),
                                stage.completed(), stage.total(), stage.unit()))));
            }

            phase = PipelinePhase.TILING;
            tileGenerator.generate(plan, prepared, workspace.outputKey(), workspace.outputPath(),
                    options, progressListener);
            successful = true;
        } catch (Exception ex) {
            log.error("[Imagery][Pipeline] 影像处理失败，阶段: {}, 源文件: {}",
                    phase.displayName, sources, ex);
            throw new IllegalStateException("影像集合处理失败: " + ex.getMessage(), ex);
        } finally {
            log.info("[Imagery][Pipeline] 影像处理流程结束，结果: {}, 最后阶段: {}, 总耗时: {} ms",
                    successful ? "成功" : "失败", phase.displayName,
                    elapsedMillis(pipelineStartedAt));
        }
    }

    private GeoTiffPreprocessor.PreparedRaster preprocessRaster(
            Path source, int nativeZoom, ImageryTileOptions options,
            Consumer<ProgressStage> progressListener) {
        long startedAt = System.nanoTime();
        log.info("[Imagery][Pipeline] 开始预处理阶段，源文件: {}", source);
        GeoTiffPreprocessor.PreparedRaster prepared = preprocessor.prepare(
                source, nativeZoom, options, progressListener);
        log.info("[Imagery][Pipeline] 预处理阶段完成，处理文件: {}, 已优化: {}, 命中缓存: {}, 耗时: {} ms",
                prepared.parts().getFirst().path(), prepared.optimized(), prepared.cacheHit(),
                elapsedMillis(startedAt));
        return prepared;
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
