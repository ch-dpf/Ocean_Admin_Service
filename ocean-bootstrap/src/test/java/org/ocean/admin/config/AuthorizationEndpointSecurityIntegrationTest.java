package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 验证浏览器授权请求在未认证时进入登录页，而不是返回 Bearer 401。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationEndpointSecurityIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("ocean.bootstrap.oauth-client.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Test
    void redirectsUnauthenticatedAuthorizationRequestToLogin() throws Exception {
        URI authorize = URI.create("http://localhost:" + port + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=ocean-admin-web"
                + "&scope=openid%20profile"
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A3000%2Flogin%2Foauth2%2Fcode%2Focean-admin"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                + "&code_challenge_method=S256");
        HttpRequest request = HttpRequest.newBuilder(authorize)
                .header("Accept", "text/html")
                .GET()
                .build();

        HttpResponse<Void> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/login"));
    }
}
