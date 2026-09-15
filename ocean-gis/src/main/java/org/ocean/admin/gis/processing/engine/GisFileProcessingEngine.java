package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.nio.file.Path;
import java.util.function.Consumer;

/** 单文件切片引擎适配器。 */
public interface GisFileProcessingEngine {

    GisProcessingType type();

    void validate(GisFileMeta fileMeta, Path inputPath);

    void process(GisProcessingExecution execution, Consumer<String> outputListener);

    /** 处理无需文件元数据、直接来自服务器目录的输入。 */
    default void processFolder(
            Path inputFolder,
            GisProcessingWorkspace workspace,
            Consumer<String> outputListener) {
        throw new UnsupportedOperationException(type().displayName() + "引擎不支持目录输入");
    }
}
