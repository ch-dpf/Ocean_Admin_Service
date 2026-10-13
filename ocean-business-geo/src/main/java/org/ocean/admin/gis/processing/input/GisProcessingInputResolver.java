package org.ocean.admin.gis.processing.input;

import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;

import java.nio.file.Path;
import java.util.List;

/** 将不同来源转换为统一、可持久化的处理输入。 */
public interface GisProcessingInputResolver {
    GisInputSourceType type();

    PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo);

    List<Path> resolveRuntime(List<GisProcessingInput> inputs);

    record PreparedInputs(List<GisProcessingInput> inputs, Runnable rollback) {
        public PreparedInputs {
            inputs = List.copyOf(inputs);
            rollback = rollback == null ? () -> { } : rollback;
        }
    }
}
