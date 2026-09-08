package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 验证应用创建的数据库连接统一使用东八区会话时区。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DatabaseTimeZoneIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void configuresEveryDatabaseSessionForChinaStandardTime() {
        assertThat(jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class))
                .isEqualTo("Asia/Shanghai");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT EXTRACT(TIMEZONE FROM CURRENT_TIMESTAMP)::integer", Integer.class))
                .isEqualTo(8 * 60 * 60);
    }
}
