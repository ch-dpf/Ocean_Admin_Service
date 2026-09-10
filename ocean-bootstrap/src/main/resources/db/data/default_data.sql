--
-- Data for Name: sys_role; Type: TABLE DATA; Schema: ocean_platform; Owner: -
--

INSERT INTO ocean_platform.sys_role (id, role_code, role_name, description, sort_order, status, create_time, update_time, deleted) VALUES (1, 'SUPER_ADMIN', '超级管理员', '拥有所有权限', 1, 1, '2026-09-10 16:46:02.85921', '2026-09-10 16:46:02.85921', 0);
INSERT INTO ocean_platform.sys_role (id, role_code, role_name, description, sort_order, status, create_time, update_time, deleted) VALUES (2, 'ADMIN', '管理员', '系统管理员', 2, 1, '2026-09-10 16:46:02.85921', '2026-09-10 16:46:02.85921', 0);
INSERT INTO ocean_platform.sys_role (id, role_code, role_name, description, sort_order, status, create_time, update_time, deleted) VALUES (3, 'USER', '普通用户', '普通用户', 3, 1, '2026-09-10 16:46:02.85921', '2026-09-10 16:46:02.85921', 0);


--
-- Data for Name: sys_user; Type: TABLE DATA; Schema: ocean_platform; Owner: -
--

INSERT INTO ocean_platform.sys_user (id, username, password, real_name, email, phone, avatar, status, deleted) VALUES (1, 'admin', '$2a$10$LCbsRaEryT0/j8.MuJ0Mz.0hMuut2yF1gO9dEr/m7/BXsESfWJSBO', '系统管理员', NULL, NULL, NULL, 1,  0);

--
-- Data for Name: sys_user_role; Type: TABLE DATA; ocean_platform: public; Owner: -
--

INSERT INTO ocean_platform.sys_user_role (id, user_id, role_id, create_time) VALUES (1, 1, 1, '2026-09-10 16:46:02.860389');