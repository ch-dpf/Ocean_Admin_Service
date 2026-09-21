-- 文件操作记录是不可变的历史事实，详情展示不应依赖可能被修改或移入回收站的文件元数据。
ALTER TABLE ocean_gis.gis_file_opt_record_item
    ADD COLUMN storage_name VARCHAR(255),
    ADD COLUMN storage_key VARCHAR(1000),
    ADD COLUMN storage_type VARCHAR(20),
    ADD COLUMN extension VARCHAR(32),
    ADD COLUMN sha256 VARCHAR(64),
    ADD COLUMN uploaded_by BIGINT,
    ADD COLUMN upload_status VARCHAR(20),
    ADD COLUMN cleanup_status VARCHAR(20),
    ADD COLUMN update_time TIMESTAMP;

-- 为 V4 已迁移的历史明细固化快照；逻辑删除的元数据仍保留在表中，因此可正常回填。
UPDATE ocean_gis.gis_file_opt_record_item item
SET storage_name = fm.storage_name,
    storage_key = fm.storage_key,
    storage_type = fm.storage_type,
    extension = fm.extension,
    sha256 = fm.sha256,
    uploaded_by = fm.uploaded_by,
    upload_status = fm.upload_status,
    cleanup_status = fm.cleanup_status,
    update_time = fm.update_time
FROM ocean_gis.gis_file_meta fm
WHERE fm.id = item.file_meta_id;

UPDATE ocean_gis.gis_file_opt_record_item
SET upload_status = CASE operation_status
        WHEN 'SUCCESS' THEN 'READY'
        WHEN 'FAILED' THEN 'FAILED'
        ELSE 'PENDING'
    END
WHERE upload_status IS NULL;

COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.storage_name IS '操作发生时的系统存储文件名快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.storage_key IS '操作发生时的存储路径或对象存储 Key 快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.storage_type IS '操作发生时的存储类型快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.extension IS '操作发生时的扩展名快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.sha256 IS '操作发生时的 SHA-256 快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.uploaded_by IS '操作发生时的上传人快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.upload_status IS '操作发生时的上传状态快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.cleanup_status IS '操作发生时的资源清理状态快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.update_time IS '操作完成时的文件更新时间快照';
