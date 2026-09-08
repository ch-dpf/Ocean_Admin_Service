package org.ocean.admin.config;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 在空 IAM 库中创建首位全局管理员，不提供任何已有账号的密码重置能力。 */
@Component
@EnableConfigurationProperties(BootstrapAdminProperties.class)
final class InitialAdminBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialAdminBootstrap.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,63}");
    private static final long BOOTSTRAP_LOCK_ID = 6_264_374_646_334_052_353L;
    private static final int BCRYPT_MAX_BYTES = 72;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;
    private final AdminPasswordSource passwordSource;

    InitialAdminBootstrap(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            PasswordEncoder passwordEncoder,
            BootstrapAdminProperties properties,
            AdminPasswordSource passwordSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.passwordSource = passwordSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            return;
        }

        String username = normalizeUsername(properties.username());
        Boolean created = transactionTemplate.execute(status -> initialize(username));
        if (Boolean.TRUE.equals(created)) {
            LOGGER.info("Initial administrator '{}' created", username);
        } else {
            LOGGER.info("Initial administrator '{}' already exists; bootstrap skipped", username);
        }
    }

    private boolean initialize(String username) {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + BOOTSTRAP_LOCK_ID + ")");
        if (isInitializedAdministrator(username)) {
            return false;
        }

        Long userCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ocean_platform.iam_user", Long.class);
        if (userCount == null || userCount != 0) {
            throw new IllegalStateException(
                    "Initial administrator bootstrap refused because IAM already contains users");
        }

        UUID superAdminRoleId = findSuperAdminRole();
        char[] password = passwordSource.readPassword();
        try {
            PasswordPolicy policy = loadPasswordPolicy();
            validatePassword(password, policy);
            String passwordHash = passwordEncoder.encode(CharBuffer.wrap(password));
            UUID userId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO ocean_platform.iam_user (
                        id, username, username_normalized, password_hash, status,
                        permanent_valid, password_changed_at
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', TRUE, now())
                    """, userId, username, username.toLowerCase(Locale.ROOT), passwordHash);
            jdbcTemplate.update("""
                    INSERT INTO ocean_platform.iam_user_role (user_id, role_id, platform_id)
                    VALUES (?, ?, NULL)
                    """, userId, superAdminRoleId);
            return true;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private boolean isInitializedAdministrator(String username) {
        Boolean initialized = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ocean_platform.iam_user u
                      JOIN ocean_platform.iam_user_role ur ON ur.user_id = u.id
                      JOIN ocean_platform.iam_role r ON r.id = ur.role_id
                     WHERE u.username_normalized = ?
                       AND u.deleted_at IS NULL
                       AND r.role_code = 'SUPER_ADMIN'
                       AND r.scope_type = 'GLOBAL'
                       AND r.status = 'ENABLED'
                )
                """, Boolean.class, username.toLowerCase(Locale.ROOT));
        return Boolean.TRUE.equals(initialized);
    }

    private UUID findSuperAdminRole() {
        List<UUID> roleIds = jdbcTemplate.queryForList("""
                SELECT id
                  FROM ocean_platform.iam_role
                 WHERE role_code = 'SUPER_ADMIN'
                   AND scope_type = 'GLOBAL'
                   AND platform_id IS NULL
                   AND status = 'ENABLED'
                """, UUID.class);
        if (roleIds.size() != 1) {
            throw new IllegalStateException("Exactly one enabled global SUPER_ADMIN role is required");
        }
        return roleIds.getFirst();
    }

    private PasswordPolicy loadPasswordPolicy() {
        return jdbcTemplate.queryForObject("""
                SELECT min_password_length, require_uppercase, require_lowercase,
                       require_digit, require_special
                  FROM ocean_platform.iam_security_policy
                 WHERE id = 1
                """, (resultSet, rowNumber) -> new PasswordPolicy(
                resultSet.getInt("min_password_length"),
                resultSet.getBoolean("require_uppercase"),
                resultSet.getBoolean("require_lowercase"),
                resultSet.getBoolean("require_digit"),
                resultSet.getBoolean("require_special")));
    }

    private static String normalizeUsername(String configuredUsername) {
        String username = configuredUsername == null ? "" : configuredUsername.strip();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalStateException(
                    "Bootstrap administrator username must be 3-64 ASCII letters, digits, dot, underscore or hyphen");
        }
        return username;
    }

    private static void validatePassword(char[] password, PasswordPolicy policy) {
        if (password == null || password.length == 0) {
            throw new IllegalStateException(
                    "Missing " + EnvironmentAdminPasswordSource.PASSWORD_ENVIRONMENT_VARIABLE);
        }
        if (password.length < policy.minimumLength()) {
            throw new IllegalStateException("Bootstrap administrator password is shorter than the security policy");
        }
        boolean uppercase = false;
        boolean lowercase = false;
        boolean digit = false;
        boolean special = false;
        for (char character : password) {
            if (Character.isWhitespace(character)) {
                throw new IllegalStateException("Bootstrap administrator password must not contain whitespace");
            }
            uppercase |= Character.isUpperCase(character);
            lowercase |= Character.isLowerCase(character);
            digit |= Character.isDigit(character);
            special |= !Character.isLetterOrDigit(character);
        }
        if (policy.requireUppercase() && !uppercase
                || policy.requireLowercase() && !lowercase
                || policy.requireDigit() && !digit
                || policy.requireSpecial() && !special) {
            throw new IllegalStateException("Bootstrap administrator password does not satisfy the security policy");
        }
        int encodedBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password)).remaining();
        if (encodedBytes > BCRYPT_MAX_BYTES) {
            throw new IllegalStateException("Bootstrap administrator password exceeds the encoder limit");
        }
    }

    private record PasswordPolicy(
            int minimumLength,
            boolean requireUppercase,
            boolean requireLowercase,
            boolean requireDigit,
            boolean requireSpecial) {
    }
}
