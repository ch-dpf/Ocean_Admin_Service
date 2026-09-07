package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AuthorizationSchemaCompatibilityTest {

    private static final String MIGRATION = "db/migration/V3__add_oauth2_authorization_server.sql";
    private static final String AUTHORIZATION_SCHEMA =
            "org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql";
    private static final String CONSENT_SCHEMA =
            "org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql";
    private static final String CLIENT_SCHEMA =
            "org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql";

    @Test
    void migrationContainsEveryOfficialJdbcColumnWithoutSchemaDrift() throws IOException {
        String migration = read(MIGRATION);

        assertThat(columns(migration, "oauth2_registered_client"))
                .isEqualTo(columns(read(CLIENT_SCHEMA), "oauth2_registered_client"));
        assertThat(columns(migration, "oauth2_authorization"))
                .isEqualTo(columns(read(AUTHORIZATION_SCHEMA), "oauth2_authorization"));
        assertThat(columns(migration, "oauth2_authorization_consent"))
                .isEqualTo(columns(read(CONSENT_SCHEMA), "oauth2_authorization_consent"));
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static Set<String> columns(String sql, String table) {
        Pattern tablePattern = Pattern.compile(
                "(?is)CREATE\\s+TABLE\\s+(?:ocean_platform\\.)?" + Pattern.quote(table)
                        + "\\s*\\((.*?)\\);" );
        Matcher tableMatcher = tablePattern.matcher(sql);
        assertThat(tableMatcher.find()).as("CREATE TABLE %s", table).isTrue();

        Pattern columnPattern = Pattern.compile("(?m)^\\s{4}([a-z][a-z0-9_]*)\\s+");
        Matcher columnMatcher = columnPattern.matcher(tableMatcher.group(1));
        Set<String> columns = new LinkedHashSet<>();
        while (columnMatcher.find()) {
            String candidate = columnMatcher.group(1);
            if (!Set.of("primary", "constraint", "foreign", "unique", "check").contains(candidate)) {
                columns.add(candidate);
            }
        }
        return columns;
    }
}
