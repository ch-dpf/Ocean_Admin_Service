-- 文件元数据只描述文件本身；上传、下载批次与文件的关系由操作明细维护。
CREATE TABLE ocean_gis.gis_file_opt_record_item (
    id                  BIGINT        PRIMARY KEY,
    record_id           BIGINT        NOT NULL,
    file_meta_id        BIGINT,
    sequence_no         INTEGER       NOT NULL,
    original_name       VARCHAR(255)  NOT NULL,
    operation_status    VARCHAR(20)   NOT NULL,
    error_message       VARCHAR(1000),
    size_bytes          BIGINT        NOT NULL DEFAULT 0,
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gis_file_opt_record_item_record
        FOREIGN KEY (record_id) REFERENCES ocean_gis.gis_file_opt_record (id),
    CONSTRAINT fk_gis_file_opt_record_item_file
        FOREIGN KEY (file_meta_id) REFERENCES ocean_gis.gis_file_meta (id),
    CONSTRAINT uk_gis_file_opt_record_item_sequence
        UNIQUE (record_id, sequence_no),
    CONSTRAINT ck_gis_file_opt_record_item_status
        CHECK (operation_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_gis_file_opt_record_item_size
        CHECK (size_bytes >= 0)
);

COMMENT ON TABLE ocean_gis.gis_file_opt_record_item IS 'GIS 文件上传下载批次的逐文件处理明细';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.file_meta_id IS '对应文件元数据；文件形成前失败时允许为空';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.operation_status IS '本次操作状态：PENDING、RUNNING、SUCCESS、FAILED';

CREATE INDEX idx_gis_file_opt_record_item_record
    ON ocean_gis.gis_file_opt_record_item (record_id, sequence_no);

CREATE INDEX idx_gis_file_opt_record_item_file
    ON ocean_gis.gis_file_opt_record_item (file_meta_id, create_time)
    WHERE file_meta_id IS NOT NULL;

CREATE UNIQUE INDEX uk_gis_file_opt_record_item_file
    ON ocean_gis.gis_file_opt_record_item (record_id, file_meta_id)
    WHERE file_meta_id IS NOT NULL;

-- 迁移已有上传批次与文件元数据关系。使用负数 ID，避免与应用生成的正数雪花 ID 冲突。
WITH existing_items AS (
    SELECT
        -ROW_NUMBER() OVER (ORDER BY fm.import_export_record_id, fm.create_time, fm.id) AS item_id,
        fm.import_export_record_id AS record_id,
        fm.id AS file_meta_id,
        ROW_NUMBER() OVER (
            PARTITION BY fm.import_export_record_id
            ORDER BY fm.create_time, fm.id
        ) AS sequence_no,
        fm.original_name,
        CASE
            WHEN fm.upload_status = 'READY' THEN 'SUCCESS'
            WHEN fm.upload_status = 'FAILED' THEN 'FAILED'
            ELSE 'PENDING'
        END AS operation_status,
        fm.error_message,
        fm.size_bytes,
        fm.create_time
    FROM ocean_gis.gis_file_meta fm
    WHERE fm.import_export_record_id IS NOT NULL
)
INSERT INTO ocean_gis.gis_file_opt_record_item (
    id, record_id, file_meta_id, sequence_no, original_name,
    operation_status, error_message, size_bytes, create_time
)
SELECT
    item_id, record_id, file_meta_id, sequence_no, original_name,
    operation_status, error_message, size_bytes, create_time
FROM existing_items;

DROP INDEX IF EXISTS ocean_gis.idx_gis_file_meta_import_export_record;

ALTER TABLE ocean_gis.gis_file_meta
    DROP CONSTRAINT IF EXISTS fk_gis_file_meta_import_export_record;

ALTER TABLE ocean_gis.gis_file_meta
    DROP COLUMN IF EXISTS import_export_record_id;
