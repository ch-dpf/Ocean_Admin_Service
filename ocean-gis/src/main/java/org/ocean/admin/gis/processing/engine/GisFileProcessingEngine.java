package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.nio.file.Path;
import java.util.function.Consumer;

/** 单文件切片引擎适配器。 */
public interface GisFileProcessingEngine {

    GisProcessingType type();

    void validate(GisFileMeta fileMeta, Path inputPath);

    void process(GisProcessingExecution execution, Consumer<String> outputListener);
}
