-- 注册统一管理后台自身，固定 UUID 便于后续迁移、测试和部署脚本稳定引用。
INSERT INTO ocean_platform.iam_platform (
    id, platform_code, platform_name, entry_url, description, status, sort_order
) VALUES
    ('10000000-0000-0000-0000-000000000001', 'OCEAN_ADMIN', '统一管理后台', NULL, '用户、日志和工作台管理入口', 'ENABLED', 1)
ON CONFLICT DO NOTHING;

-- 初始化全局及平台级内置角色。
INSERT INTO ocean_platform.iam_role (
    id, platform_id, role_code, role_name, scope_type, status, description
) VALUES
    ('20000000-0000-0000-0000-000000000001', NULL, 'SUPER_ADMIN', '超级管理员', 'GLOBAL', 'ENABLED', '跨平台管理权限'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'PLATFORM_ADMIN', '平台管理员', 'PLATFORM', 'ENABLED', '当前平台管理权限'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'AUDITOR', '审计员', 'PLATFORM', 'ENABLED', '日志和审计只读权限')
ON CONFLICT DO NOTHING;

-- 初始化管理后台最小权限集合，编码格式为“平台:资源:动作”。
INSERT INTO ocean_platform.iam_permission (
    id, platform_id, permission_code, permission_name, resource, action, status
) VALUES
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'admin:user:read', '查看用户', 'user', 'read', 'ENABLED'),
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'admin:user:write', '管理用户', 'user', 'write', 'ENABLED'),
    ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'admin:audit:read', '查看审计日志', 'audit', 'read', 'ENABLED'),
    ('30000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 'admin:workbench:view', '查看工作台', 'workbench', 'view', 'ENABLED')
ON CONFLICT DO NOTHING;

-- 平台管理员拥有全部内置权限，审计员仅拥有审计和工作台只读权限。
INSERT INTO ocean_platform.iam_role_permission (role_id, permission_id)
VALUES
    ('20000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001'),
    ('20000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002'),
    ('20000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000003'),
    ('20000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000004'),
    ('20000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003'),
    ('20000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000004')
ON CONFLICT DO NOTHING;

-- 创建唯一的全局安全策略，未显式赋值的字段使用数据库默认值。
INSERT INTO ocean_platform.iam_security_policy (id)
VALUES (1)
ON CONFLICT DO NOTHING;

-- 安全约束：不预置默认用户或密码。
-- 首位管理员必须通过受控的初始化命令或部署密钥创建。
