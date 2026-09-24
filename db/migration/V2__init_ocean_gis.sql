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
-- GIS 文件导入导出记录
-- =========================================================

CREATE TABLE ocean_gis.gis_file_opt_record (
    id                  BIGINT        PRIMARY KEY,
    record_no           VARCHAR(64)   NOT NULL,
    operation_type      VARCHAR(16)   NOT NULL,
    data_set_id         BIGINT        NOT NULL,
    total_count         INTEGER       NOT NULL DEFAULT 0,
    completed_count     INTEGER       NOT NULL DEFAULT 0,
    failed_count        INTEGER       NOT NULL DEFAULT 0,
    record_status       VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    current_stage       VARCHAR(32),
    result_storage_key  VARCHAR(1000),
    error_message       VARCHAR(1000),
    operator_id         BIGINT,
    version             BIGINT        NOT NULL DEFAULT 0,
    start_time          TIMESTAMP,
    finish_time         TIMESTAMP,
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_file_opt_record_no
        UNIQUE (record_no),
    CONSTRAINT fk_gis_file_opt_record_data_set
        FOREIGN KEY (data_set_id) REFERENCES ocean_gis.gis_data_set (id),
    CONSTRAINT ck_gis_file_opt_record_operation
        CHECK (operation_type IN ('IMPORT', 'EXPORT')),
    CONSTRAINT ck_gis_file_opt_record_status
        CHECK (record_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT ck_gis_file_opt_record_counts
        CHECK (
            total_count >= 0
            AND completed_count >= 0
            AND failed_count >= 0
            AND completed_count + failed_count <= total_count
        ),
    CONSTRAINT ck_gis_file_opt_record_version
        CHECK (version >= 0),
    CONSTRAINT ck_gis_file_opt_record_timing
        CHECK (
            (record_status = 'QUEUED' AND finish_time IS NULL)
            OR (record_status = 'RUNNING' AND start_time IS NOT NULL AND finish_time IS NULL)
            OR (
                record_status IN ('COMPLETED', 'PARTIAL_FAILED', 'FAILED')
                AND start_time IS NOT NULL
                AND finish_time IS NOT NULL
                AND completed_count + failed_count = total_count
            )
        ),
    CONSTRAINT ck_gis_file_opt_record_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_file_opt_record IS 'GIS 文件导入导出记录';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record.record_no IS '对外暴露的稳定导入导出记录编号';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record.operation_type IS '操作类型：IMPORT-导入，EXPORT-导出';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record.record_status IS '记录状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record.result_storage_key IS '导出结果的相对存储路径或对象存储 Key';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record.version IS '状态版本号，用于进度快照与实时事件合并';

CREATE INDEX idx_gis_file_opt_record_data_set
    ON ocean_gis.gis_file_opt_record (data_set_id, operation_type, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_opt_record_status
    ON ocean_gis.gis_file_opt_record (record_status, create_time)
    WHERE deleted = 0;


-- =========================================================
-- GIS 文件元数据
-- =========================================================

CREATE TABLE ocean_gis.gis_file_meta (
    id                       BIGINT        PRIMARY KEY,
    data_set_id              BIGINT        NOT NULL,
    original_name            VARCHAR(255)  NOT NULL,
    storage_name             VARCHAR(255),
    storage_key              VARCHAR(1000),
    storage_type             VARCHAR(20)   NOT NULL DEFAULT 'LOCAL',
    extension                VARCHAR(32),
    size_bytes               BIGINT        NOT NULL DEFAULT 0,
    sha256                   VARCHAR(64),
    uploaded_by              BIGINT,
    upload_status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    cleanup_status           VARCHAR(20)   NOT NULL DEFAULT 'NOT_REQUIRED',
    error_message            VARCHAR(1000),
    create_time              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT ck_gis_file_meta_storage_type
        CHECK (storage_type IN ('LOCAL', 'MINIO', 'S3')),
    CONSTRAINT ck_gis_file_meta_size
        CHECK (size_bytes >= 0),
    CONSTRAINT ck_gis_file_meta_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_gis_file_meta_upload_status
        CHECK (upload_status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT ck_gis_file_meta_cleanup_status
        CHECK (cleanup_status IN ('NOT_REQUIRED', 'PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_gis_file_meta_storage_required
        CHECK (upload_status = 'FAILED' OR (storage_name IS NOT NULL AND storage_key IS NOT NULL)),
    CONSTRAINT ck_gis_file_meta_cleanup_upload_status
        CHECK (upload_status = 'FAILED' OR cleanup_status = 'NOT_REQUIRED'),
    CONSTRAINT ck_gis_file_meta_cleanup_pending_key
        CHECK (
            cleanup_status NOT IN ('PENDING', 'FAILED')
            OR (upload_status = 'FAILED' AND storage_key IS NOT NULL)
        ),
    CONSTRAINT ck_gis_file_meta_cleanup_completed
        CHECK (
            cleanup_status <> 'COMPLETED'
            OR (upload_status = 'FAILED' AND storage_key IS NULL)
        )
);

COMMENT ON TABLE ocean_gis.gis_file_meta IS 'GIS 文件元数据';
COMMENT ON COLUMN ocean_gis.gis_file_meta.storage_key IS '相对存储路径或对象存储 Key';
COMMENT ON COLUMN ocean_gis.gis_file_meta.storage_type IS '存储类型：LOCAL、MINIO 或 S3';
COMMENT ON COLUMN ocean_gis.gis_file_meta.cleanup_status IS '失败文件资源清理状态：NOT_REQUIRED、PENDING、COMPLETED、FAILED';

CREATE UNIQUE INDEX uk_gis_file_meta_active_storage
    ON ocean_gis.gis_file_meta (storage_type, storage_key)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_meta_data_set
    ON ocean_gis.gis_file_meta (data_set_id, create_time DESC)
    WHERE deleted = 0;

CREATE INDEX idx_gis_file_meta_sha256
    ON ocean_gis.gis_file_meta (sha256)
    WHERE deleted = 0 AND sha256 IS NOT NULL;

-- =========================================================
-- GIS 文件操作逐文件明细
-- =========================================================

CREATE TABLE ocean_gis.gis_file_opt_record_item (
    id                  BIGINT        PRIMARY KEY,
    record_id           BIGINT        NOT NULL,
    file_meta_id        BIGINT,
    sequence_no         INTEGER       NOT NULL,
    original_name       VARCHAR(255)  NOT NULL,
    operation_status    VARCHAR(20)   NOT NULL,
    error_message       VARCHAR(1000),
    size_bytes          BIGINT        NOT NULL DEFAULT 0,
    storage_name        VARCHAR(255),
    storage_key         VARCHAR(1000),
    storage_type        VARCHAR(20),
    extension           VARCHAR(32),
    sha256              VARCHAR(64),
    uploaded_by         BIGINT,
    upload_status       VARCHAR(20),
    cleanup_status      VARCHAR(20),
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP,
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
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.storage_name IS '操作发生时的系统存储文件名快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.storage_key IS '操作发生时的存储路径或对象存储 Key 快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.storage_type IS '操作发生时的存储类型快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.extension IS '操作发生时的扩展名快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.sha256 IS '操作发生时的 SHA-256 快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.uploaded_by IS '操作发生时的上传人快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.upload_status IS '操作发生时的上传状态快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.cleanup_status IS '操作发生时的资源清理状态快照';
COMMENT ON COLUMN ocean_gis.gis_file_opt_record_item.update_time IS '操作完成时的文件更新时间快照';

CREATE INDEX idx_gis_file_opt_record_item_record
    ON ocean_gis.gis_file_opt_record_item (record_id, sequence_no);

CREATE INDEX idx_gis_file_opt_record_item_file
    ON ocean_gis.gis_file_opt_record_item (file_meta_id, create_time)
    WHERE file_meta_id IS NOT NULL;

CREATE UNIQUE INDEX uk_gis_file_opt_record_item_file
    ON ocean_gis.gis_file_opt_record_item (record_id, file_meta_id)
    WHERE file_meta_id IS NOT NULL;


-- =========================================================
-- GIS 处理任务
-- =========================================================

CREATE TABLE ocean_gis.gis_processing_task (
    id                        BIGINT       PRIMARY KEY,
    task_no                   VARCHAR(64)  NOT NULL,
    task_name                 VARCHAR(200) NOT NULL,
    processing_type           VARCHAR(20)  NOT NULL,
    source_type               VARCHAR(20)  NOT NULL,
    parameters                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    parameter_schema_version  INTEGER      NOT NULL DEFAULT 1,
    request_fingerprint       VARCHAR(64),
    priority                  INTEGER      NOT NULL DEFAULT 0,
    total_count               BIGINT       NOT NULL DEFAULT 0,
    completed_count           BIGINT       NOT NULL DEFAULT 0,
    failed_count              BIGINT       NOT NULL DEFAULT 0,
    task_status               VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    current_stage             VARCHAR(32),
    error_message             VARCHAR(1000),
    start_time                TIMESTAMP,
    finish_time               TIMESTAMP,
    create_time               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                   INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_processing_task_no
        UNIQUE (task_no),
    CONSTRAINT ck_gis_processing_task_type
        CHECK (processing_type IN ('TERRAIN', 'IMAGERY', 'VECTOR')),
    CONSTRAINT ck_gis_processing_task_source
        CHECK (source_type IN ('UPLOAD', 'WORKSPACE', 'MANAGED_FILE')),
    CONSTRAINT ck_gis_processing_task_schema_version
        CHECK (parameter_schema_version > 0),
    CONSTRAINT ck_gis_processing_task_counts
        CHECK (total_count >= 0 AND completed_count >= 0 AND failed_count >= 0
            AND completed_count + failed_count <= total_count),
    CONSTRAINT ck_gis_processing_task_deleted
        CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_gis_processing_task_status
        CHECK (task_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED'))
);

COMMENT ON TABLE ocean_gis.gis_processing_task IS '静态瓦片生成任务及不可变处理参数快照';
COMMENT ON COLUMN ocean_gis.gis_processing_task.parameters IS '按处理类型保存的完整参数 JSON 快照';

CREATE INDEX idx_gis_processing_task_schedule
    ON ocean_gis.gis_processing_task (priority DESC, create_time)
    WHERE deleted = 0;

CREATE INDEX idx_gis_processing_task_status
    ON ocean_gis.gis_processing_task (task_status, priority DESC, create_time)
    WHERE deleted = 0;

CREATE INDEX idx_gis_processing_task_type
    ON ocean_gis.gis_processing_task (processing_type, source_type, create_time DESC)
    WHERE deleted = 0;

-- =========================================================
-- GIS 处理输入
-- =========================================================

CREATE TABLE ocean_gis.gis_processing_input (
    id              BIGINT       PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    sequence_no     INTEGER      NOT NULL,
    input_kind      VARCHAR(16)  NOT NULL,
    input_status    VARCHAR(20)  NOT NULL DEFAULT 'READY',
    file_meta_id    BIGINT,
    workspace_code  VARCHAR(64),
    relative_path   VARCHAR(1000),
    storage_key     VARCHAR(1000),
    original_name   VARCHAR(500) NOT NULL,
    extension       VARCHAR(32),
    size_bytes      BIGINT,
    sha256          VARCHAR(64),
    error_message   VARCHAR(1000),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gis_processing_input_task
        FOREIGN KEY (task_id) REFERENCES ocean_gis.gis_processing_task (id),
    CONSTRAINT fk_gis_processing_input_file
        FOREIGN KEY (file_meta_id) REFERENCES ocean_gis.gis_file_meta (id),
    CONSTRAINT uk_gis_processing_input_sequence
        UNIQUE (task_id, sequence_no),
    CONSTRAINT ck_gis_processing_input_sequence
        CHECK (sequence_no > 0),
    CONSTRAINT ck_gis_processing_input_kind
        CHECK (input_kind IN ('FILE', 'DIRECTORY')),
    CONSTRAINT ck_gis_processing_input_status
        CHECK (input_status IN ('READY', 'RUNNING', 'CONSUMED', 'FAILED')),
    CONSTRAINT ck_gis_processing_input_size
        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_gis_processing_input_locator
        CHECK (
            (file_meta_id IS NOT NULL AND workspace_code IS NULL
                AND relative_path IS NULL AND storage_key IS NULL)
            OR (file_meta_id IS NULL AND workspace_code IS NOT NULL
                AND relative_path IS NOT NULL AND storage_key IS NULL)
            OR (file_meta_id IS NULL AND workspace_code IS NULL
                AND relative_path IS NULL AND storage_key IS NOT NULL)
        )
);

COMMENT ON TABLE ocean_gis.gis_processing_input IS '处理任务输入快照，统一上传、受控工作空间和已管理文件';
COMMENT ON COLUMN ocean_gis.gis_processing_input.relative_path IS '受控工作空间根目录下的相对路径，禁止保存绝对路径';

CREATE INDEX idx_gis_processing_input_task
    ON ocean_gis.gis_processing_input (task_id, sequence_no);

CREATE INDEX idx_gis_processing_input_file
    ON ocean_gis.gis_processing_input (file_meta_id)
    WHERE file_meta_id IS NOT NULL;

-- =========================================================
-- GIS 静态瓦片集
-- =========================================================

CREATE TABLE ocean_gis.gis_tile_set (
    id              BIGINT        PRIMARY KEY,
    task_id         BIGINT        NOT NULL,
    tile_type       VARCHAR(20)   NOT NULL,
    tile_set_status VARCHAR(20)   NOT NULL DEFAULT 'BUILDING',
    output_key      VARCHAR(1000) NOT NULL,
    target_crs      VARCHAR(100),
    tile_profile    VARCHAR(100),
    output_format   VARCHAR(100),
    min_zoom        INTEGER,
    max_zoom        INTEGER,
    manifest_key    VARCHAR(1000),
    error_message   VARCHAR(1000),
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gis_tile_set_task
        FOREIGN KEY (task_id) REFERENCES ocean_gis.gis_processing_task (id),
    CONSTRAINT uk_gis_tile_set_task UNIQUE (task_id),
    CONSTRAINT uk_gis_tile_set_output UNIQUE (output_key),
    CONSTRAINT ck_gis_tile_set_type
        CHECK (tile_type IN ('TERRAIN', 'IMAGERY', 'VECTOR')),
    CONSTRAINT ck_gis_tile_set_status
        CHECK (tile_set_status IN ('BUILDING', 'READY', 'FAILED')),
    CONSTRAINT ck_gis_tile_set_zoom
        CHECK ((min_zoom IS NULL AND max_zoom IS NULL)
            OR (min_zoom BETWEEN 0 AND 22 AND max_zoom BETWEEN 0 AND 22
                AND min_zoom <= max_zoom))
);

COMMENT ON TABLE ocean_gis.gis_tile_set IS '一次处理任务唯一生成的静态瓦片集';
COMMENT ON COLUMN ocean_gis.gis_tile_set.output_key IS '处理存储根目录下的瓦片集 Key';

CREATE INDEX idx_gis_tile_set_type_status
    ON ocean_gis.gis_tile_set (tile_type, tile_set_status, create_time DESC);

-- =========================================================
-- GIS 瓦片服务发布记录
-- =========================================================

CREATE TABLE ocean_gis.gis_publication (
    id                  BIGINT       PRIMARY KEY,
    service_code        VARCHAR(64)  NOT NULL,
    tile_set_id         BIGINT       NOT NULL,
    data_set_id         BIGINT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    publish_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_gis_publication_code UNIQUE (service_code),
    CONSTRAINT uk_gis_publication_tile_set UNIQUE (tile_set_id),
    CONSTRAINT fk_gis_publication_tile_set
        FOREIGN KEY (tile_set_id) REFERENCES ocean_gis.gis_tile_set (id),
    CONSTRAINT fk_gis_publication_data_set
        FOREIGN KEY (data_set_id) REFERENCES ocean_gis.gis_data_set (id),
    CONSTRAINT ck_gis_publication_status
        CHECK (status IN ('PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_gis_publication_deleted
        CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE ocean_gis.gis_publication IS '静态瓦片集的公开发布记录';
COMMENT ON COLUMN ocean_gis.gis_publication.service_code IS '公开服务 URL 中使用的稳定编码';
COMMENT ON COLUMN ocean_gis.gis_publication.tile_set_id IS '被发布的静态瓦片集 ID';

CREATE INDEX idx_gis_publication_status
    ON ocean_gis.gis_publication (status, publish_time DESC)
    WHERE deleted = 0;
