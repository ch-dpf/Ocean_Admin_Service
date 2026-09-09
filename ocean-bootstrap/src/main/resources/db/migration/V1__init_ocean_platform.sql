CREATE SCHEMA IF NOT EXISTS ocean_platform;

-- =========================================================
-- 用户与权限
-- =========================================================

CREATE TABLE ocean_platform.sys_user (
                                         id                          BIGSERIAL PRIMARY KEY,
                                         username                    VARCHAR(50)  NOT NULL UNIQUE,
                                         password                    VARCHAR(200) NOT NULL,
                                         real_name                   VARCHAR(50),
                                         email                       VARCHAR(100),
                                         phone                       VARCHAR(20),
                                         avatar                      VARCHAR(500),
                                         status                      INTEGER      NOT NULL DEFAULT 1,
                                         last_login_time             TIMESTAMP,
                                         last_login_ip               VARCHAR(50),
                                         max_login_devices           INTEGER      NOT NULL DEFAULT 1,
                                         valid_from                  TIMESTAMP,
                                         valid_to                    TIMESTAMP,
                                         is_permanent_valid          INTEGER      NOT NULL DEFAULT 1,
                                         failed_password_attempts    INTEGER      NOT NULL DEFAULT 0,
                                         lock_until                  TIMESTAMP,
                                         lock_level                  INTEGER      NOT NULL DEFAULT 0,
                                         last_password_error_time    TIMESTAMP,
                                         manual_unlock_time          TIMESTAMP,
                                         manual_unlock_by            VARCHAR(64),
                                         create_time                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                         update_time                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                         deleted                     INTEGER      NOT NULL DEFAULT 0
);

COMMENT ON TABLE ocean_platform.sys_user IS '用户信息表';
COMMENT ON COLUMN ocean_platform.sys_user.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN ocean_platform.sys_user.max_login_devices IS '允许同时登录的设备数量';
COMMENT ON COLUMN ocean_platform.sys_user.is_permanent_valid IS '是否永久有效：0-否，1-是';
COMMENT ON COLUMN ocean_platform.sys_user.failed_password_attempts IS '连续密码错误次数';
COMMENT ON COLUMN ocean_platform.sys_user.lock_level IS '锁定等级，每次触发锁定后递增';
COMMENT ON COLUMN ocean_platform.sys_user.deleted IS '逻辑删除：0-未删除，1-已删除';

CREATE INDEX idx_user_username
    ON ocean_platform.sys_user (username);

CREATE INDEX idx_user_status
    ON ocean_platform.sys_user (status);

CREATE INDEX idx_user_create_time
    ON ocean_platform.sys_user (create_time DESC);

CREATE INDEX idx_sys_user_valid_period
    ON ocean_platform.sys_user (is_permanent_valid, valid_from, valid_to);

CREATE INDEX idx_sys_user_lock_until
    ON ocean_platform.sys_user (lock_until);


CREATE TABLE ocean_platform.sys_role (
                                         id              BIGSERIAL PRIMARY KEY,
                                         role_code       VARCHAR(50)  NOT NULL UNIQUE,
                                         role_name       VARCHAR(50)  NOT NULL,
                                         description     VARCHAR(200),
                                         sort_order      INTEGER      NOT NULL DEFAULT 0,
                                         status          INTEGER      NOT NULL DEFAULT 1,
                                         create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                         update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                         deleted         INTEGER      NOT NULL DEFAULT 0
);

COMMENT ON TABLE ocean_platform.sys_role IS '角色信息表';

CREATE INDEX idx_role_code
    ON ocean_platform.sys_role (role_code);

CREATE INDEX idx_role_status
    ON ocean_platform.sys_role (status);


CREATE TABLE ocean_platform.sys_permission (
                                               id                  BIGSERIAL PRIMARY KEY,
                                               parent_id           BIGINT       NOT NULL DEFAULT 0,
                                               permission_code     VARCHAR(100) NOT NULL UNIQUE,
                                               permission_name     VARCHAR(50)  NOT NULL,
                                               permission_type     VARCHAR(20),
                                               path                VARCHAR(200),
                                               component           VARCHAR(200),
                                               icon                VARCHAR(50),
                                               sort_order          INTEGER      NOT NULL DEFAULT 0,
                                               status              INTEGER      NOT NULL DEFAULT 1,
                                               create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               deleted             INTEGER      NOT NULL DEFAULT 0
);

COMMENT ON TABLE ocean_platform.sys_permission IS '权限资源表';

CREATE INDEX idx_permission_parent_id
    ON ocean_platform.sys_permission (parent_id);

CREATE INDEX idx_permission_code
    ON ocean_platform.sys_permission (permission_code);

CREATE INDEX idx_permission_type
    ON ocean_platform.sys_permission (permission_type);


CREATE TABLE ocean_platform.sys_platform (
                                             id              BIGSERIAL PRIMARY KEY,
                                             platform_code   VARCHAR(50)  NOT NULL UNIQUE,
                                             platform_name   VARCHAR(100) NOT NULL,
                                             description     VARCHAR(500),
                                             platform_url    VARCHAR(500),
                                             icon            VARCHAR(200),
                                             status          INTEGER      NOT NULL DEFAULT 1,
                                             sort_order      INTEGER      NOT NULL DEFAULT 0,
                                             create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             deleted         INTEGER      NOT NULL DEFAULT 0
);

COMMENT ON TABLE ocean_platform.sys_platform IS '平台信息表';

CREATE INDEX idx_platform_code
    ON ocean_platform.sys_platform (platform_code);

CREATE INDEX idx_platform_status
    ON ocean_platform.sys_platform (status);


-- =========================================================
-- 用户、平台、角色和权限关系
-- 参照 Ocean_Cloud_Service，不额外引入物理外键
-- =========================================================

CREATE TABLE ocean_platform.sys_user_platform (
                                                  id              BIGSERIAL PRIMARY KEY,
                                                  user_id         BIGINT    NOT NULL,
                                                  platform_id     BIGINT    NOT NULL,
                                                  create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  CONSTRAINT uk_sys_user_platform UNIQUE (user_id, platform_id)
);

COMMENT ON TABLE ocean_platform.sys_user_platform IS '用户的平台准入授权关系表';

CREATE INDEX idx_user_platform_user_id
    ON ocean_platform.sys_user_platform (user_id);

CREATE INDEX idx_user_platform_platform_id
    ON ocean_platform.sys_user_platform (platform_id);


CREATE TABLE ocean_platform.sys_user_role (
                                              id              BIGSERIAL PRIMARY KEY,
                                              user_id         BIGINT    NOT NULL,
                                              role_id         BIGINT    NOT NULL,
                                              create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                              CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id)
);

COMMENT ON TABLE ocean_platform.sys_user_role IS '用户角色关系表';

CREATE INDEX idx_user_role_user_id
    ON ocean_platform.sys_user_role (user_id);

CREATE INDEX idx_user_role_role_id
    ON ocean_platform.sys_user_role (role_id);


CREATE TABLE ocean_platform.sys_role_permission (
                                                    id              BIGSERIAL PRIMARY KEY,
                                                    role_id         BIGINT    NOT NULL,
                                                    permission_id   BIGINT    NOT NULL,
                                                    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                    CONSTRAINT uk_sys_role_permission UNIQUE (role_id, permission_id)
);

COMMENT ON TABLE ocean_platform.sys_role_permission IS '角色权限关系表';

CREATE INDEX idx_role_permission_role_id
    ON ocean_platform.sys_role_permission (role_id);

CREATE INDEX idx_role_permission_permission_id
    ON ocean_platform.sys_role_permission (permission_id);


-- =========================================================
-- 安全策略与锁定记录
-- =========================================================

CREATE TABLE ocean_platform.sys_security_policy (
                                                    id                      BIGINT PRIMARY KEY,
                                                    min_password_length     INTEGER   NOT NULL DEFAULT 8,
                                                    require_uppercase       INTEGER   NOT NULL DEFAULT 1,
                                                    require_lowercase       INTEGER   NOT NULL DEFAULT 1,
                                                    require_digit           INTEGER   NOT NULL DEFAULT 1,
                                                    require_special         INTEGER   NOT NULL DEFAULT 0,
                                                    lock_enabled            INTEGER   NOT NULL DEFAULT 1,
                                                    max_error_count         INTEGER   NOT NULL DEFAULT 5,
                                                    lock_base_minutes       INTEGER   NOT NULL DEFAULT 5,
                                                    lock_max_minutes        INTEGER   NOT NULL DEFAULT 720,
                                                    update_time             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                    create_time             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ocean_platform.sys_security_policy IS '密码、锁定及会话安全策略表';
COMMENT ON COLUMN ocean_platform.sys_security_policy.lock_enabled IS '是否启用锁定：0-否，1-是';

INSERT INTO ocean_platform.sys_security_policy (
    id,
    min_password_length,
    require_uppercase,
    require_lowercase,
    require_digit,
    require_special,
    lock_enabled,
    max_error_count,
    lock_base_minutes,
    lock_max_minutes
)
VALUES (1, 8, 1, 1, 1, 0, 1, 5, 5, 720)
    ON CONFLICT (id) DO NOTHING;


CREATE TABLE ocean_platform.sys_user_lock_record (
                                                     id                          BIGINT PRIMARY KEY,
                                                     user_id                     BIGINT       NOT NULL,
                                                     username                    VARCHAR(64)  NOT NULL,
                                                     real_name                   VARCHAR(64),
                                                     record_type                 VARCHAR(32)  NOT NULL,
                                                     failed_password_attempts    INTEGER      NOT NULL DEFAULT 0,
                                                     lock_level                  INTEGER      NOT NULL DEFAULT 0,
                                                     lock_until                  TIMESTAMP,
                                                     lock_minutes                INTEGER      NOT NULL DEFAULT 0,
                                                     message                     VARCHAR(500),
                                                     operator_name               VARCHAR(64),
                                                     create_time                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ocean_platform.sys_user_lock_record IS '用户锁定及解锁记录表';
COMMENT ON COLUMN ocean_platform.sys_user_lock_record.record_type IS '记录类型：LOCK或UNLOCK';

CREATE INDEX idx_sys_user_lock_record_user_time
    ON ocean_platform.sys_user_lock_record (user_id, create_time DESC);

CREATE INDEX idx_sys_user_lock_record_type_time
    ON ocean_platform.sys_user_lock_record (record_type, create_time DESC);


-- =========================================================
-- 审计日志
-- =========================================================

CREATE TABLE ocean_platform.sys_login_log (
                                              id              BIGSERIAL PRIMARY KEY,
                                              user_id         BIGINT,
                                              username        VARCHAR(50),
                                              login_type      VARCHAR(20),
                                              ip_address      VARCHAR(50),
                                              location        VARCHAR(200),
                                              browser         VARCHAR(100),
                                              os              VARCHAR(100),
                                              user_agent      VARCHAR(500),
                                              device_id       VARCHAR(100),
                                              platform        VARCHAR(50),
                                              status          INTEGER      NOT NULL DEFAULT 1,
                                              message         VARCHAR(500),
                                              login_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                              logout_time     TIMESTAMP,
                                              session_id      VARCHAR(100),
                                              deleted         INTEGER      NOT NULL DEFAULT 0
);

COMMENT ON TABLE ocean_platform.sys_login_log IS '登录日志表';
COMMENT ON COLUMN ocean_platform.sys_login_log.login_type IS '登录类型：PASSWORD、CODE或TOKEN';
COMMENT ON COLUMN ocean_platform.sys_login_log.status IS '状态：0-失败，1-成功';
COMMENT ON COLUMN ocean_platform.sys_login_log.deleted IS '逻辑删除：0-未删除，1-已删除';

CREATE INDEX idx_login_log_user_id
    ON ocean_platform.sys_login_log (user_id);

CREATE INDEX idx_login_log_login_time
    ON ocean_platform.sys_login_log (login_time DESC);

CREATE INDEX idx_login_log_status
    ON ocean_platform.sys_login_log (status);

CREATE INDEX idx_login_log_ip_address
    ON ocean_platform.sys_login_log (ip_address);

CREATE INDEX idx_sys_login_log_active_session
    ON ocean_platform.sys_login_log (user_id, session_id, login_time)
    WHERE status = 1
      AND deleted = 0
      AND logout_time IS NULL;


CREATE TABLE ocean_platform.sys_operation_log (
                                                  id                  BIGSERIAL PRIMARY KEY,
                                                  user_id             BIGINT,
                                                  username            VARCHAR(50),
                                                  module              VARCHAR(50),
                                                  operation_type      VARCHAR(20),
                                                  description         VARCHAR(500),
                                                  method              VARCHAR(200),
                                                  request_url         VARCHAR(500),
                                                  request_method      VARCHAR(10),
                                                  request_params      TEXT,
                                                  response_result     TEXT,
                                                  ip_address          VARCHAR(50),
                                                  user_agent          VARCHAR(500),
                                                  execution_time      BIGINT,
                                                  status              INTEGER      NOT NULL DEFAULT 1,
                                                  error_message       TEXT,
                                                  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  deleted             INTEGER      NOT NULL DEFAULT 0
);

COMMENT ON TABLE ocean_platform.sys_operation_log IS '操作日志表';
COMMENT ON COLUMN ocean_platform.sys_operation_log.operation_type IS
    '操作类型：INSERT、UPDATE、DELETE、QUERY、EXPORT、IMPORT、LOGIN或LOGOUT';
COMMENT ON COLUMN ocean_platform.sys_operation_log.execution_time IS '执行时长，单位毫秒';
COMMENT ON COLUMN ocean_platform.sys_operation_log.status IS '状态：0-失败，1-成功';
COMMENT ON COLUMN ocean_platform.sys_operation_log.deleted IS '逻辑删除：0-未删除，1-已删除';

CREATE INDEX idx_operation_log_user_id
    ON ocean_platform.sys_operation_log (user_id);

CREATE INDEX idx_operation_log_create_time
    ON ocean_platform.sys_operation_log (create_time DESC);

CREATE INDEX idx_operation_log_module
    ON ocean_platform.sys_operation_log (module);

CREATE INDEX idx_operation_log_status
    ON ocean_platform.sys_operation_log (status);