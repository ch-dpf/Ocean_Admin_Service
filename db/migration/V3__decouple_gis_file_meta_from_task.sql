DROP INDEX IF EXISTS ocean_gis.idx_gis_file_meta_task;

ALTER TABLE ocean_gis.gis_file_meta
    DROP COLUMN IF EXISTS task_id;
