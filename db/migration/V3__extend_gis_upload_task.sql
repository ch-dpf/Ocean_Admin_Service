ALTER TABLE ocean_gis.gis_task
    ADD COLUMN task_status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN current_stage VARCHAR(32),
    ADD COLUMN error_message VARCHAR(1000),
    ADD COLUMN start_time TIMESTAMP,
    ADD COLUMN finish_time TIMESTAMP;

ALTER TABLE ocean_gis.gis_task
    ADD CONSTRAINT ck_gis_task_status
        CHECK (task_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED'));

CREATE INDEX idx_gis_task_status
    ON ocean_gis.gis_task (task_status, priority DESC, create_time)
    WHERE deleted = 0;

ALTER TABLE ocean_gis.gis_file_meta
    ADD COLUMN task_id BIGINT,
    ADD COLUMN upload_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN error_message VARCHAR(1000);

ALTER TABLE ocean_gis.gis_file_meta
    ADD CONSTRAINT ck_gis_file_meta_upload_status
        CHECK (upload_status IN ('PENDING', 'READY', 'FAILED'));

CREATE INDEX idx_gis_file_meta_task
    ON ocean_gis.gis_file_meta (task_id, create_time)
    WHERE deleted = 0 AND task_id IS NOT NULL;
