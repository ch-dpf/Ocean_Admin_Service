package org.ocean.admin.gis.processing.input;

import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.progress.ProgressStage;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** 将不同来源转换为统一、可持久化的处理输入。 */
public interface GisProcessingInputResolver {
    GisInputSourceType type();

    PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo);

    List<Path> resolveRuntime(List<GisProcessingInput> inputs);

    /** 运行期间持有规范化资源；普通上传和工作区入口继续直接使用原有路径。 */
    default RuntimeInputs openRuntime(List<GisProcessingInput> inputs, Runnable heartbeat, Consumer<ProgressStage> progress) {
        return new RuntimeInputs(resolveRuntime(inputs), null, null);
    }

    record RuntimeInputs(List<Path> paths, Runnable verify, Runnable cleanup) implements AutoCloseable {
        public RuntimeInputs {
            paths = List.copyOf(paths);
            verify = verify == null ? () -> { } : verify;
            cleanup = cleanup == null ? () -> { } : cleanup;
        }

        public void verifySources() { verify.run(); }

        @Override public void close() { cleanup.run(); }
    }

    record PreparedInputs(List<GisProcessingInput> inputs, Runnable rollback) {
        public PreparedInputs {
            inputs = List.copyOf(inputs);
            rollback = rollback == null ? () -> { } : rollback;
        }
    }
}
