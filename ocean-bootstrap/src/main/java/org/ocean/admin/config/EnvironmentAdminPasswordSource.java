package org.ocean.admin.config;

import org.springframework.stereotype.Component;

/** 仅从操作系统环境变量读取首位管理员密码。 */
@Component
final class EnvironmentAdminPasswordSource implements AdminPasswordSource {

    static final String PASSWORD_ENVIRONMENT_VARIABLE = "OCEAN_BOOTSTRAP_ADMIN_PASSWORD";

    @Override
    public char[] readPassword() {
        String password = System.getenv(PASSWORD_ENVIRONMENT_VARIABLE);
        return password == null ? null : password.toCharArray();
    }
}
