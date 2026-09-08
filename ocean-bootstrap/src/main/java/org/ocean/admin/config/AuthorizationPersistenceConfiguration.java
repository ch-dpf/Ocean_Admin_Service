package org.ocean.admin.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Spring Authorization Server 的 JDBC 持久化配置。
 * 官方 JDBC 实现使用不带 schema 的表名，因此每个连接都显式切换到业务 schema。
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationPersistenceConfiguration {

    /** OAuth2 协议表所在的 PostgreSQL schema。 */
    static final String AUTHORIZATION_SCHEMA = "ocean_platform";

    /** 共享带 schema 的 JDBC 上下文，供协议仓储和跨表初始化事务共同使用。 */
    @Bean
    AuthorizationJdbcOperations authorizationJdbcOperations(DataSource dataSource) {
        DataSource schemaDataSource = new SchemaDataSource(dataSource, AUTHORIZATION_SCHEMA);
        return new AuthorizationJdbcOperations(
                new JdbcTemplate(schemaDataSource),
                new DataSourceTransactionManager(schemaDataSource));
    }

    /** 创建 OAuth2 注册客户端仓储。 */
    @Bean
    RegisteredClientRepository registeredClientRepository(AuthorizationJdbcOperations operations) {
        return new JdbcRegisteredClientRepository(operations.jdbcTemplate());
    }

    /** 创建授权、令牌及其元数据的持久化服务。 */
    @Bean
    OAuth2AuthorizationService authorizationService(
            AuthorizationJdbcOperations operations,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(
                operations.jdbcTemplate(), registeredClientRepository);
    }

    /** 创建用户授权同意记录的持久化服务。 */
    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            AuthorizationJdbcOperations operations,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(
                operations.jdbcTemplate(), registeredClientRepository);
    }

    /** 同时持有共享 JdbcTemplate 与绑定同一 DataSource 的事务管理器。 */
    record AuthorizationJdbcOperations(
            JdbcTemplate jdbcTemplate,
            DataSourceTransactionManager transactionManager) {
    }

    /** 在借出连接时设置默认 schema 的轻量 DataSource 装饰器。 */
    private static final class SchemaDataSource extends DelegatingDataSource {
        private final String schema;

        private SchemaDataSource(DataSource targetDataSource, String schema) {
            super(targetDataSource);
            this.schema = schema;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return applySchema(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return applySchema(super.getConnection(username, password));
        }

        /**
         * 设置 schema；若设置失败则立即关闭连接，防止状态未知的连接泄漏回连接池。
         */
        private Connection applySchema(Connection connection) throws SQLException {
            try {
                connection.setSchema(schema);
                return connection;
            } catch (SQLException schemaFailure) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    schemaFailure.addSuppressed(closeFailure);
                }
                throw schemaFailure;
            }
        }
    }
}
