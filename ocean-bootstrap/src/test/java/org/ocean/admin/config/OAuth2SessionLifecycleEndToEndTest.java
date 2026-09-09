package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 通过真实 HTTP 端点验证授权码签发、刷新轮换、重放检测与 RFC 7009 撤销。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class OAuth2SessionLifecycleEndToEndTest {

    private static final UUID USER_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID PLATFORM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PLATFORM_ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID AUDITOR_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final String USERNAME = "oauth-e2e-user";
    private static final String PASSWORD = "test-password";
    private static final String CLIENT_ID = "ocean-admin-web";
    private static final String REDIRECT_URI =
            "http://127.0.0.1:3000/login/oauth2/code/ocean-admin";
    private static final String VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE =
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");
    private static final Pattern REFRESH_TOKEN =
            Pattern.compile("\"refresh_token\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN =
            Pattern.compile("\"access_token\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern LOGIN_ACCESS_TOKEN =
            Pattern.compile("\"accessToken\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern LOGIN_REFRESH_TOKEN =
            Pattern.compile("\"refreshToken\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern LOGIN_SESSION_ID =
            Pattern.compile("\"sessionId\"\s*:\s*\"([^\"]+)\"");

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("ocean.bootstrap.oauth-client.enabled", () -> "true");
        registry.add("ocean.bootstrap.oauth-client.require-consent", () -> "false");
        registry.add("ocean.security.session-cache.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RefreshTokenLifecycleService lifecycleService;

    private HttpClient client;

    @BeforeEach
    void prepareUserAndClient() {
        jdbcTemplate.update("DELETE FROM ocean_platform.iam_auth_session");
        jdbcTemplate.update("DELETE FROM ocean_platform.iam_user WHERE id = ?", USER_ID);
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user (
                    id, username, username_normalized, password_hash, status
                ) VALUES (?, ?, ?, '{noop}test-password', 'ACTIVE')
                """, USER_ID, USERNAME, USERNAME);
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user_role (user_id, role_id, platform_id)
                VALUES (?, ?, ?)
                """, USER_ID, PLATFORM_ADMIN_ROLE_ID, PLATFORM_ID);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Test
    void signsRotatesDetectsReplayAndRevokesThroughSasEndpoints() throws Exception {
        login();

        TokenPair firstTokens = exchangeAuthorizationCode(authorize());
        String firstRefreshToken = firstTokens.refreshToken();
        assertThat(tokenStatuses()).containsExactly("ISSUED");
        assertThat(storedTokenHashes()).allMatch(value -> value.startsWith("sha256:"));
        assertThat(storedTokenHashes()).noneMatch(value -> value.contains(firstRefreshToken));
        UUID firstSessionId = activeSessionIds().getFirst();
        assertThat(redisTemplate.hasKey(cacheKey(firstSessionId))).isTrue();
        assertThat(redisTemplate.opsForValue().get(cacheKey(firstSessionId)))
                .doesNotContain(firstRefreshToken);

        redisTemplate.delete(cacheKey(firstSessionId));
        assertThat(lifecycleService.findActiveSession(firstSessionId)).isPresent();
        assertThat(redisTemplate.hasKey(cacheKey(firstSessionId))).isTrue();
        HttpResponse<String> me = getWithBearer("/api/v1/me", firstTokens.accessToken());
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains(USER_ID.toString(), "PLATFORM_ADMIN", "admin:user:write");
        assertThat(getWithBearer(
                "/api/v1/users?keyword=" + USERNAME, firstTokens.accessToken()).body())
                .contains(USER_ID.toString(), USERNAME, "\"total\":1");
        assertThat(getWithBearer("/api/v1/roles", firstTokens.accessToken()).body())
                .contains(PLATFORM_ADMIN_ROLE_ID.toString(), "SUPER_ADMIN", "PLATFORM_ADMIN", "AUDITOR");
        assertThat(getWithBearer("/api/v1/permissions", firstTokens.accessToken()).body())
                .contains("admin:user:read", "admin:user:write");
        assertThat(getWithBearer("/api/v1/me/sessions", firstTokens.accessToken()).body())
                .contains(firstSessionId.toString());

        assertThat(postWithBearer(
                "/api/v1/users/" + USER_ID + "/roles/" + AUDITOR_ROLE_ID,
                firstTokens.accessToken()).statusCode()).isEqualTo(200);
        assertThat(getWithBearer(
                "/api/v1/users/" + USER_ID + "/roles", firstTokens.accessToken()).body())
                .contains("AUDITOR");
        assertThat(deleteWithBearer(
                "/api/v1/users/" + USER_ID + "/roles/" + AUDITOR_ROLE_ID,
                firstTokens.accessToken()).statusCode()).isEqualTo(204);

        HttpResponse<String> refresh = postForm("/oauth2/token", Map.of(
                "grant_type", "refresh_token",
                "refresh_token", firstRefreshToken,
                "client_id", CLIENT_ID));
        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
        String secondRefreshToken = jsonValue(refresh.body(), REFRESH_TOKEN);
        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);
        assertThat(tokenStatuses()).containsExactly("USED", "ISSUED");
        assertThat(redisTemplate.hasKey(cacheKey(firstSessionId))).isTrue();

        HttpResponse<String> replay = postForm("/oauth2/token", Map.of(
                "grant_type", "refresh_token",
                "refresh_token", firstRefreshToken,
                "client_id", CLIENT_ID));
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(replay.body()).contains("invalid_grant");
        assertThat(tokenStatuses()).containsExactly("USED", "REVOKED");
        assertThat(sessionRevokeReasons()).containsExactly("REFRESH_TOKEN_REUSE");
        assertThat(redisTemplate.hasKey(cacheKey(firstSessionId))).isFalse();
        assertThat(getWithBearer("/api/v1/me", firstTokens.accessToken()).statusCode()).isEqualTo(401);

        TokenPair revocableTokens = exchangeAuthorizationCode(authorize());
        String revocableToken = revocableTokens.refreshToken();
        UUID revocableSessionId = activeSessionIds().getFirst();
        assertThat(redisTemplate.hasKey(cacheKey(revocableSessionId))).isTrue();
        assertThat(getWithBearer("/api/v1/me", revocableTokens.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> revocation = postForm("/oauth2/revoke", Map.of(
                "token", revocableToken,
                "token_type_hint", "refresh_token",
                "client_id", CLIENT_ID));
        assertThat(revocation.statusCode()).isEqualTo(200);
        assertThat(sessionRevokeReasons())
                .containsExactlyInAnyOrder("REFRESH_TOKEN_REUSE", "OAUTH2_TOKEN_REVOCATION");
        assertThat(activeTokenCount()).isZero();
        assertThat(redisTemplate.hasKey(cacheKey(revocableSessionId))).isFalse();
        assertThat(getWithBearer("/api/v1/me", revocableTokens.accessToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void logsInRefreshesAndLogsOutThroughFirstPartyRestApi() throws Exception {
        HttpResponse<String> rejected = postJson("/api/user/login", """
                {"username":"oauth-e2e-user","password":"wrong-password"}
                """);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(401);

        HttpResponse<String> missingPlatform = postJson("/api/user/platform-login", """
                {"username":"oauth-e2e-user","password":"test-password"}
                """);
        assertThat(missingPlatform.statusCode()).as(missingPlatform.body()).isEqualTo(400);

        HttpResponse<String> wrongPlatform = postJson("/api/user/platform-login", """
                {
                  "username":"oauth-e2e-user",
                  "password":"test-password",
                  "platformCode":"OTHER_PLATFORM"
                }
                """);
        assertThat(wrongPlatform.statusCode()).as(wrongPlatform.body()).isEqualTo(403);

        HttpResponse<String> login = postJson("/api/user/login", """
                {
                  "username":"oauth-e2e-user",
                  "password":"test-password",
                  "platformCode":"OCEAN_ADMIN",
                  "deviceId":"postman-device",
                  "deviceName":"Postman"
                }
                """);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(login.body()).contains(
                USER_ID.toString(), USERNAME, "OCEAN_ADMIN", "PLATFORM_ADMIN", "admin:user:write");
        String accessToken = jsonValue(login.body(), LOGIN_ACCESS_TOKEN);
        String refreshToken = jsonValue(login.body(), LOGIN_REFRESH_TOKEN);
        UUID sessionId = UUID.fromString(jsonValue(login.body(), LOGIN_SESSION_ID));

        assertThat(lifecycleService.findActiveSession(sessionId)).isPresent();
        assertThat(redisTemplate.hasKey(cacheKey(sessionId))).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT device_id FROM ocean_platform.iam_auth_session WHERE session_id = ?
                """, String.class, sessionId)).isEqualTo("postman-device");
        assertThat(getWithBearer("/api/v1/me", accessToken).statusCode()).isEqualTo(200);

        HttpResponse<String> refresh = postForm("/oauth2/token", Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "client_id", CLIENT_ID));
        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
        String refreshedAccessToken = jsonValue(refresh.body(), ACCESS_TOKEN);

        HttpResponse<String> logout = postWithBearer("/api/user/logout", refreshedAccessToken);
        assertThat(logout.statusCode()).as(logout.body()).isEqualTo(204);
        assertThat(lifecycleService.findActiveSession(sessionId)).isEmpty();
        assertThat(redisTemplate.hasKey(cacheKey(sessionId))).isFalse();
        assertThat(getWithBearer("/api/v1/me", refreshedAccessToken).statusCode()).isEqualTo(401);
        assertThat(tokenStatuses()).containsExactly("USED", "REVOKED");
    }

    private void login() throws Exception {
        HttpResponse<String> loginPage = get("/login");
        String csrf = match(loginPage.body(), CSRF);
        HttpResponse<String> login = postForm("/login", Map.of(
                "username", USERNAME,
                "password", PASSWORD,
                "_csrf", csrf));
        assertThat(login.statusCode()).isBetween(302, 303);
    }

    private String authorize() throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", CLIENT_ID);
        parameters.put("scope", "openid profile");
        parameters.put("redirect_uri", REDIRECT_URI);
        parameters.put("code_challenge", CHALLENGE);
        parameters.put("code_challenge_method", "S256");
        parameters.put("state", UUID.randomUUID().toString());
        HttpResponse<String> response = get("/oauth2/authorize?" + form(parameters));
        assertThat(response.statusCode()).isBetween(302, 303);
        String location = response.headers().firstValue("Location").orElseThrow();
        return decode(match(location, CODE));
    }

    private TokenPair exchangeAuthorizationCode(String code) throws Exception {
        HttpResponse<String> response = postForm("/oauth2/token", Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "client_id", CLIENT_ID,
                "code_verifier", VERIFIER));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new TokenPair(
                jsonValue(response.body(), ACCESS_TOKEN),
                jsonValue(response.body(), REFRESH_TOKEN));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithBearer(String path, String accessToken) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + accessToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String accessToken) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + accessToken)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, Map<String, String> parameters)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(parameters)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String jsonValue(String json, Pattern pattern) {
        return match(json, pattern);
    }

    private static String match(String value, Pattern pattern) {
        var matcher = pattern.matcher(value);
        assertThat(matcher.find()).as(value).isTrue();
        return matcher.group(1);
    }

    private java.util.List<String> tokenStatuses() {
        return jdbcTemplate.queryForList("""
                SELECT status FROM ocean_platform.iam_refresh_token ORDER BY issued_at, sequence_number
                """, String.class);
    }

    private java.util.List<String> storedTokenHashes() {
        return jdbcTemplate.queryForList("""
                SELECT token_hash FROM ocean_platform.iam_refresh_token ORDER BY issued_at, sequence_number
                """, String.class);
    }

    private java.util.List<String> sessionRevokeReasons() {
        return jdbcTemplate.queryForList("""
                SELECT revoke_reason FROM ocean_platform.iam_auth_session
                 WHERE revoked_at IS NOT NULL ORDER BY revoke_reason
                """, String.class);
    }

    private long activeTokenCount() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ocean_platform.iam_refresh_token WHERE status = 'ISSUED'
                """, Long.class);
        return count == null ? 0 : count;
    }

    private java.util.List<UUID> activeSessionIds() {
        return jdbcTemplate.queryForList("""
                SELECT session_id FROM ocean_platform.iam_auth_session
                 WHERE revoked_at IS NULL ORDER BY created_at DESC
                """, UUID.class);
    }

    private static String cacheKey(UUID sessionId) {
        return "ocean:iam:session:" + sessionId;
    }

    private record TokenPair(String accessToken, String refreshToken) {
    }
}
