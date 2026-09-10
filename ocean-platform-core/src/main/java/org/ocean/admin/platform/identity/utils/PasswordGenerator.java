package org.ocean.admin.platform.identity.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码生成工具 - 用于生成Bcrypt加密密码
 *
 * @author DeepOcean
 * @since 2026-09-10
 */
public class PasswordGenerator {

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        // 生成123456的BCrypt密码
        String password = "123456";
        String encodedPassword = encoder.encode(password);

        System.out.println("原始密码: " + password);
        System.out.println("加密后: " + encodedPassword);
        System.out.println("\n请在schema.sql中替换以下行:");
        System.out.println("VALUES (1, 'admin', '" + encodedPassword + "', '系统管理员', 'ADMIN', 1)");
    }
}
