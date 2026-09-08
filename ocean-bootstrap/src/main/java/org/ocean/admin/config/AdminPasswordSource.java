package org.ocean.admin.config;

/** 为管理员初始化命令提供一次性密码字符副本。 */
@FunctionalInterface
interface AdminPasswordSource {

    char[] readPassword();
}
