package org.ocean.admin.gis.processing.input;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisProcessingParameters.VectorProcessingParameters;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.processing.*;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.service.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** 在后台固定工作数据；排队期间修订发生变化时拒绝使用不同版本的数据。 */
@Component
@RequiredArgsConstructor
public class VectorResourceInputResolver implements GisProcessingInputResolver {
    private final VectorResourceService resources;
    private final VectorDataSetStyleService styles;
    private final VectorStyleService schemes;
    private final GeoPackageStore store;
    private final GisProcessingStorageService storage;
    private final ProcessingTaskRecordService tasks;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    @Override public GisInputSourceType type() { return GisInputSourceType.VECTOR_RESOURCE; }

    @Override public PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo) {
        var parameters = (VectorProcessingParameters) command.parameters();
        var state = styles.require(parameters.dataSetId(), false);
        if (!state.getDataRevision().equals(parameters.dataRevision()))
            throw new IllegalArgumentException("工作数据已变化，请刷新地图后重新生成");
        var input = new GisProcessingInput();
        input.setSequenceNo(1);
        input.setInputKind("FILE");
        input.setInputStatus("READY");
        input.setSourceDataSetId(parameters.dataSetId());
        input.setOriginalName("source.gpkg");
        input.setExtension("gpkg");
        input.setStorageKey(storage.taskWorkspace(GisProcessingType.VECTOR, taskNo).outputKey());
        return new PreparedInputs(List.of(input), null);
    }

    @Override public List<Path> resolveRuntime(List<GisProcessingInput> inputs) {
        if (inputs.size() != 1) throw new IllegalArgumentException("矢量任务只接受一个工作数据快照");
        return List.of(storage.workspaceFromOutputKey(inputs.getFirst().getStorageKey()).tempPath().resolve("source.gpkg"));
    }

    @Override public RuntimeInputs openRuntime(List<GisProcessingInput> inputs, Runnable heartbeat, Consumer<ProgressStage> progress) {
        var paths = resolveRuntime(inputs);
        var parameters = (VectorProcessingParameters) tasks.readParameters(tasks.getRequired(inputs.getFirst().getTaskId()));
        progress.accept(ProgressStage.indeterminate("VECTOR_SNAPSHOT", "固定工作数据及分类规则"));
        transactions.executeWithoutResult(tx -> {
            var state = styles.require(parameters.dataSetId(), true);
            if (!state.getDataRevision().equals(parameters.dataRevision()))
                throw new IllegalArgumentException("任务排队期间工作数据已变化，请按最新数据重新生成");
            schemes.validateScheme(state, parameters.schemeSnapshot().path("definition"));
            heartbeat.run();
            try {
                var frozen = resources.freeze(parameters.dataSetId(), "PUBLICATION");
                Path input = paths.getFirst();
                Files.createDirectories(input.getParent());
                Files.copy(store.path(frozen.getStorageKey()), input);
                json.writeValue(input.resolveSibling("source.json").toFile(), json.readTree(frozen.getDefinitionJson()));
                // 任务持有专属物理副本；临时登记的共享资源随后由既有回收流程清理。
                resources.retire(frozen.getId());
            } catch (IOException ex) {
                throw new IllegalStateException("固定矢量工作数据失败", ex);
            }
        });
        heartbeat.run();
        return new RuntimeInputs(paths, null, null);
    }
}
