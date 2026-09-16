CREATE SCHEMA IF NOT EXISTS ocean_gis;

-- =========================================================
-- GIS 数据集
-- =========================================================

CREATE TABLE ocean_gis.gis_data_set (
    id              BIGINT       PRIMARY KEY,
    data_set_name   VARCHAR(100) NOT NULL,
    category_id     BIGINT       NOT NULL,
    data_set_code   VARCHAR(64)  NOT NULL,
    description     VARCHAR(500),
    file_count      INTEGER      NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_gis_data_set_category
        CHECK (category_id BETWEEN 0 AND 2),
    CONSTRAINT ck_gis_data_set_file_count
        CHECK (file_count >= 0),
    CONSTRAINT ck_gis_data_set_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT uk_gis_data_set_code
        UNIQUE (data_set_code)
);

COMMENT ON TABLE ocean_gis.gis_data_set IS 'GIS 数据集';
COMMENT ON COLUMN ocean_gis.gis_data_set.category_id IS '数据类别：0-影像数据，1-地形数据，2-矢量数据';
COMMENT ON COLUMN ocean_gis.gis_data_set.deleted IS '逻辑删除：0-未删除，1-已删除';

CREATE UNIQUE INDEX uk_gis_data_set_active_name
    ON ocean_gis.gis_data_set (data_set_name)
    WHERE deleted = 0;

CREATE INDEX idx_gis_data_set_category
    ON ocean_gis.gis_data_set (category_id)
    WHERE deleted = 0;

CREATE INDEX idx_gis_data_set_create_time
    ON ocean_gis.gis_data_set (create_time DESC)
    WHERE deleted = 0;


-- =========================================================
-- GIS 文件元数据
-- =========================================================

CREATE TABLE ocean_gis.gis_file_meta (
    id              BIGINT        PRIMARY KEY,
    data_set_id     BIGINT        NOT NULL,
    original_name   VARCHAR(255)  NOT NULL,
    storage_name    VARCHAR(255)  NOT NULL,
    storage_key     VARCHAR(1000) NOT NULL,
    storage_type    VARCHAR(20)   NOT NULL DEFAULT 'LOCAL',
    extension       VARCHAR(32),
    size_bytes      BIGINT        NOT NULL DEFAULT 0,
    sha256          VARCHAR(64),
    uploaded_by     BIGINT,
    task_id         BIGINT,
    upload_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(1000),
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT ck_gis_file_meta_storage_type
        CHECK (storage_type IN ('LOCAL', 'MINIO', 'S3')),
    CONSTRAINT ck_gis_file_meta_size
        CHECK (size_bytes >= 0),
    CONSTRAINT ck_gis_file_meta_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_gis_file_meta_upload_status
        CHECK (upload_status IN ('PENDING', 'READY', 'FAILED'))
);

COMMENT ON TABLE ocean_gis.gis_file_meta IS 'GIS 文件元数据';
COMMENT ON COLUMN ocean_gis.gis_file_meta.storage_key IS '相对存储路径或对象存储 Key';
COMMENT ON COLUMN ocean_gis.gis_file_meta.storage_type IS '存储类型：LOCAL、MINIO 或 S3';

CREATE UNIQUE INDEX uk_gis_file_meta_active_storage
    ON ocean_gis.gis_file_meta (storage_type, storage_key)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_meta_data_set
    ON ocean_gis.gis_file_meta (data_set_id, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_meta_sha256
    ON ocean_gis.gis_file_meta (sha256)
    WHERE deleted = 0 AND sha256 IS NOT NULL;

CREATE INDEX idx_gis_file_meta_task
    ON ocean_gis.gis_file_meta (task_id, create_time)
    WHERE deleted = 0 AND task_id IS NOT NULL;


-- =========================================================
-- GIS 异步任务
-- =========================================================

CREATE TABLE ocean_gis.gis_task (
    id                  BIGINT       PRIMARY KEY,
    task_no             VARCHAR(64)  NOT NULL,
    task_name           VARCHAR(200) NOT NULL,
    task_type           BIGINT       NOT NULL,
    priority            INTEGER      NOT NULL DEFAULT 0,
    total_count         BIGINT       NOT NULL DEFAULT 0,
    completed_count     BIGINT       NOT NULL DEFAULT 0,
    failed_count        BIGINT       NOT NULL DEFAULT 0,
    data_set_id         BIGINT,
    parent_task_id      BIGINT,
    root_task_id        BIGINT,
    task_status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    current_stage       VARCHAR(32),
    error_message       VARCHAR(1000),
    start_time          TIMESTAMP,
    finish_time         TIMESTAMP,
    processing_type     VARCHAR(20),
    source_file_meta_id BIGINT,
    output_key          VARCHAR(1000),
    target_crs          VARCHAR(100),
    tile_profile        VARCHAR(100),
    output_format       VARCHAR(100),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_task_no
        UNIQUE (task_no),
    CONSTRAINT ck_gis_task_type
        CHECK (task_type BETWEEN 1 AND 4),
    CONSTRAINT ck_gis_task_counts
        CHECK (total_count >= 0 AND completed_count >= 0 AND failed_count >= 0),
    CONSTRAINT ck_gis_task_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_gis_task_status
        CHECK (task_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT ck_gis_task_processing_type
        CHECK (processing_type IS NULL OR processing_type IN ('TERRAIN', 'IMAGERY', 'VECTOR'))
);

COMMENT ON TABLE ocean_gis.gis_task IS 'GIS 异步任务';
COMMENT ON COLUMN ocean_gis.gis_task.task_type IS '任务类型：1-上传，2-切片，3-发布，4-导出或下载';
COMMENT ON COLUMN ocean_gis.gis_task.processing_type IS '切片处理类型：TERRAIN、IMAGERY、VECTOR';
COMMENT ON COLUMN ocean_gis.gis_task.source_file_meta_id IS '切片任务的源文件元数据 ID';
COMMENT ON COLUMN ocean_gis.gis_task.output_key IS '切片产物在处理存储根目录下的相对 Key';

CREATE INDEX idx_gis_task_data_set
    ON ocean_gis.gis_task (data_set_id, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_task_parent
    ON ocean_gis.gis_task (parent_task_id)
    WHERE deleted = 0 AND parent_task_id IS NOT NULL;

CREATE INDEX idx_gis_task_root
    ON ocean_gis.gis_task (root_task_id)
    WHERE deleted = 0 AND root_task_id IS NOT NULL;

CREATE INDEX idx_gis_task_schedule
    ON ocean_gis.gis_task (priority DESC, create_time)
    WHERE deleted = 0;

CREATE INDEX idx_gis_task_status
    ON ocean_gis.gis_task (task_status, priority DESC, create_time)
    WHERE deleted = 0;

CREATE INDEX idx_gis_task_source_processing
    ON ocean_gis.gis_task (source_file_meta_id, processing_type, create_time DESC)
    WHERE deleted = 0 AND source_file_meta_id IS NOT NULL;

CREATE UNIQUE INDEX uk_gis_task_active_file_processing
    ON ocean_gis.gis_task (source_file_meta_id, processing_type)
    WHERE deleted = 0
      AND source_file_meta_id IS NOT NULL
      AND task_status IN ('QUEUED', 'RUNNING');

-- =========================================================
-- Cesium 地形发布记录
-- =========================================================

CREATE TABLE ocean_gis.gis_terrain_publication (
    id                  BIGINT       PRIMARY KEY,
    service_code        VARCHAR(64)  NOT NULL,
    source_task_id      BIGINT       NOT NULL,
    publish_task_id     BIGINT       NOT NULL,
    data_set_id         BIGINT,
    output_key          VARCHAR(1000) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    publish_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_terrain_publication_code UNIQUE (service_code),
    CONSTRAINT uk_gis_terrain_publication_source UNIQUE (source_task_id),
    CONSTRAINT ck_gis_terrain_publication_status
        CHECK (status IN ('PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_gis_terrain_publication_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_terrain_publication IS 'Cesium quantized-mesh 地形发布记录';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.service_code IS '公开服务 URL 中使用的稳定编码';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.source_task_id IS '已完成的地形切片任务 ID';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.publish_task_id IS '对应的发布任务 ID';
COMMENT ON COLUMN ocean_gis.gis_terrain_publication.output_key IS '处理存储根目录下的切片产物 Key';

CREATE INDEX idx_gis_terrain_publication_status
    ON ocean_gis.gis_terrain_publication (status, publish_time DESC)
    WHERE deleted = 0;

-- =========================================================
-- 多文件处理任务逐文件结果
-- =========================================================

CREATE TABLE ocean_gis.gis_processing_task_file (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES ocean_gis.gis_task(id),
    file_index INTEGER NOT NULL,
    original_name VARCHAR(500) NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    output_key VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    error_message VARCHAR(1000),
    CONSTRAINT uk_gis_processing_task_file_index UNIQUE (task_id, file_index),
    CONSTRAINT ck_gis_processing_task_file_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_gis_processing_task_file_task
    ON ocean_gis.gis_processing_task_file (task_id, file_index);
