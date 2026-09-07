package org.ocean.admin.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@Configuration(proxyBeanMethods = false)
public class AuthorizationPersistenceConfiguration {

    static final String AUTHORIZATION_SCHEMA = "ocean_platform";

    @Bean
    RegisteredClientRepository registeredClientRepository(DataSource dataSource) {
        return new JdbcRegisteredClientRepository(authorizationJdbcTemplate(dataSource));
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            DataSource dataSource,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(
                authorizationJdbcTemplate(dataSource), registeredClientRepository);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            DataSource dataSource,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(
                authorizationJdbcTemplate(dataSource), registeredClientRepository);
    }

    private JdbcTemplate authorizationJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(new SchemaDataSource(dataSource, AUTHORIZATION_SCHEMA));
    }

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
