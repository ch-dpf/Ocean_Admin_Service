ALTER TABLE ocean_gis.gis_task
    ADD COLUMN processing_type VARCHAR(20),
    ADD COLUMN source_file_meta_id BIGINT,
    ADD COLUMN output_key VARCHAR(1000);

ALTER TABLE ocean_gis.gis_task
    ADD CONSTRAINT ck_gis_task_processing_type
        CHECK (processing_type IS NULL OR processing_type IN ('TERRAIN', 'IMAGERY', 'VECTOR'));

COMMENT ON COLUMN ocean_gis.gis_task.processing_type IS '切片处理类型：TERRAIN、IMAGERY、VECTOR';
COMMENT ON COLUMN ocean_gis.gis_task.source_file_meta_id IS '切片任务的源文件元数据 ID';
COMMENT ON COLUMN ocean_gis.gis_task.output_key IS '切片产物在处理存储根目录下的相对 Key';

CREATE INDEX idx_gis_task_source_processing
    ON ocean_gis.gis_task (source_file_meta_id, processing_type, create_time DESC)
    WHERE deleted = 0 AND source_file_meta_id IS NOT NULL;

CREATE UNIQUE INDEX uk_gis_task_active_file_processing
    ON ocean_gis.gis_task (source_file_meta_id, processing_type)
    WHERE deleted = 0
      AND source_file_meta_id IS NOT NULL
      AND task_status IN ('QUEUED', 'RUNNING');
