# 数据库设计
ocean platform 模式
sys_user        用户信息表
sys_role        角色信息表
sys_permission  权限资源表
sys_platform    平台信息表

sys_user_platform       用户的平台准入授权
sys_user_role           用户角色关系
sys_role_permission     角色权限关系

sys_security_policy     密码、锁定、会话安全策略
sys_user_lock_record    锁定记录
sys_login_log   系统登录日志
sys_operation_log   系统操作日志
sys_exception_log   系统异常日志

ocean gis 模式
gis_data_set    数据集表
gis_file_meta   文件元数据表
gis_task        任务表


gis_terrain_publication     地形发布表
gis_processing_task_file    多文件处理任务逐文件结果