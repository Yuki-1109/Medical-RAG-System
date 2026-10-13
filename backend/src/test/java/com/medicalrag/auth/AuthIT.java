package com.medicalrag.auth;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalrag.testutil.RestartTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@Import(AuthIT.PrivateProbe.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIT {
    // Test-only credentials, never a product default or written to the user's database.
    private static final String PASSWORD = "A2-Integration-Password-2026";
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @BeforeEach
    void clearTestAdministrator() {
        jdbc.update("DELETE FROM users");
    }

    @Test
    void firstSetupIsPrivateAfterwardsAndCannotBeRepeated() throws Exception {
        try (ApiClient client = new ApiClient(port)) {
            assertThat(json.readTree(client.get("/api/auth/setup-status").body())).isEqualTo(json.readTree("{\"initialized\":false}"));
            String token = client.csrf();
            var created = client.setup("Doctor", PASSWORD, token);
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.body()).contains("doctor").doesNotContain("password", "hash", "singleton", PASSWORD);
            assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(json.readTree(client.get("/api/auth/setup-status").body())).isEqualTo(json.readTree("{\"initialized\":true}"));
            var repeated = client.setup("another", PASSWORD, token);
            assertThat(repeated.statusCode()).isEqualTo(409);
            assertThat(repeated.body()).contains("SETUP_ALREADY_COMPLETED").doesNotContain(PASSWORD);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
            assertThat(client.post("/api/auth/register", "", "application/json", token).statusCode()).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentInitializationCreatesExactlyOneAdministratorAndDatabaseRejectsBypasses(boolean sameUsername) throws Exception {
        int competitors = 8;
        var ready = new CountDownLatch(competitors);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(competitors)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < competitors; i++) {
                final int candidate = i;
                futures.add(executor.submit(() -> {
                    try (ApiClient client = new ApiClient(port)) {
                        String token = client.csrf();
                        ready.countDown();
                        if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                        return client.setup(sameUsername ? "doctor" : "doctor" + candidate, PASSWORD, token).statusCode();
                    }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (var result : futures) results.add(result.get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn(code -> code == 201).hasSize(1);
            assertThat(results).filteredOn(code -> code == 409).hasSize(7);
        } finally { start.countDown(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (username,password_hash) VALUES ('bypass',?)", hash))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (username,password_hash,singleton_key) VALUES ('bypass',?,false)", hash))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void bcryptLoginSessionFixationAndCredentialPrivacyAreEnforced() throws Exception {
        try (ApiClient client = new ApiClient(port)) {
            String token = client.csrf();
            assertThat(client.setup("doctor", PASSWORD, token).statusCode()).isEqualTo(201);
            String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
            assertThat(hash).startsWith("$2a$12$").hasSize(60).isNotEqualTo(PASSWORD);
            assertThat(passwords.matches(PASSWORD, hash)).isTrue();
            String anonymousSession = client.sessionId();
            var wrong = client.login("doctor", "incorrect-test-password", token);
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(wrong.body()).contains("INVALID_CREDENTIALS").doesNotContain(hash, PASSWORD);
            assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
            var login = client.login("DOCTOR", PASSWORD, token);
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.body()).contains("doctor").doesNotContain("password", "hash", PASSWORD);
            assertThat(client.sessionId()).isNotEqualTo(anonymousSession);
            assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(200);
            try (ApiClient forged = new ApiClient(port)) {
                assertThat(forged.rawCookieGet("/api/auth/me", "JSESSIONID=" + anonymousSession).statusCode()).isEqualTo(401);
            }
        }
    }

    @Test
    void csrfProtectsSetupLoginLogoutAndPrivateWritesAndRotatesAfterLogin() throws Exception {
        try (ApiClient client = new ApiClient(port)) {
            assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(client.get("/api/test/private").statusCode()).isEqualTo(401);
            String token = client.csrf();
            var missing = client.setup("doctor", PASSWORD, null);
            assertThat(missing.statusCode()).isEqualTo(403);
            assertThat(missing.body()).contains("CSRF_INVALID").doesNotContain(PASSWORD);
            assertThat(client.setup("doctor", PASSWORD, "invalid-csrf").statusCode()).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
            assertThat(client.setup("doctor", PASSWORD, token).statusCode()).isEqualTo(201);
            assertThat(client.login("doctor", PASSWORD, null).statusCode()).isEqualTo(403);
            assertThat(client.login("doctor", PASSWORD, "invalid-csrf").statusCode()).isEqualTo(403);
            assertThat(client.login("doctor", PASSWORD, token).statusCode()).isEqualTo(200);
            assertThat(client.post("/api/test/private", "", "application/json", null).statusCode()).isEqualTo(403);
            assertThat(client.post("/api/test/private", "", "application/json", "invalid-csrf").statusCode()).isEqualTo(403);
            assertThat(client.post("/api/auth/logout", "", "application/json", null).statusCode()).isEqualTo(403);
            assertThat(client.post("/api/auth/logout", "", "application/json", token).statusCode()).isEqualTo(403);
            String authenticatedToken = client.csrf();
            assertThat(client.post("/api/test/private", "", "application/json", authenticatedToken).statusCode()).isEqualTo(200);
            try (ApiClient anonymous = new ApiClient(port)) {
                assertThat(anonymous.post("/api/test/private", "", "application/json", anonymous.csrf()).statusCode()).isEqualTo(401);
                assertThat(anonymous.rawCookieGet("/api/auth/me", "JSESSIONID=made-up-test-session").statusCode()).isEqualTo(401);
            }
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedNonAdministratorReceives403() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    }

    @Test
    void logoutInvalidatesOldSessionAndAllowsNewLogin() throws Exception {
        try (ApiClient client = new ApiClient(port)) {
            String token = client.csrf();
            client.setup("doctor", PASSWORD, token);
            assertThat(client.login("doctor", PASSWORD, token).statusCode()).isEqualTo(200);
            String oldSession = client.sessionId();
            var logout = client.post("/api/auth/logout", "", "application/json", client.csrf());
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(logout.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.contains("JSESSIONID=") && cookie.contains("Max-Age=0"));
            assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(client.rawCookieGet("/api/auth/me", "JSESSIONID=" + oldSession).statusCode()).isEqualTo(401);
            assertThat(client.login("doctor", PASSWORD, client.csrf()).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void invalidInputNeverEchoesCredentialsAndOverlongBcryptInputCannotLogin() throws Exception {
        try (ApiClient client = new ApiClient(port)) {
            String token = client.csrf();
            String invalidSecret = "too-short";
            var invalid = client.setup("doctor", invalidSecret, token);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).doesNotContain(invalidSecret);
            assertThat(client.setup("bad username", PASSWORD, token).statusCode()).isEqualTo(400);
            assertThat(client.setup("doctor", "汉".repeat(25), token).statusCode()).isEqualTo(400);
            assertThat(client.setup("doctor", "", token).statusCode()).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
            String maximum = "a".repeat(72);
            assertThat(client.setup("doctor", maximum, token).statusCode()).isEqualTo(201);
            assertThat(client.login("doctor", maximum + "suffix", token).statusCode()).isEqualTo(401);
            assertThat(client.login("nonexistent", maximum + "suffix", token).statusCode()).isEqualTo(401);
            assertThat(client.login("doctor", maximum, token).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void cookiesAreHttpOnlyLaxAndNoHtmlBasicOrPublicRegistrationIsGenerated() throws Exception {
        try (ApiClient client = new ApiClient(port)) {
            var response = client.get("/api/auth/csrf");
            String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
            assertThat(cookie).contains("JSESSIONID=", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Secure");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(client.get("/login").statusCode()).isEqualTo(401);
            assertThat(client.get("/api/auth/login").body()).doesNotContain("<html", "<form");
            var basic = client.basicGet("/api/auth/me");
            assertThat(basic.statusCode()).isEqualTo(401);
            assertThat(basic.headers().firstValue("WWW-Authenticate")).isEmpty();
            assertThat(client.get("/api/auth/register").statusCode()).isEqualTo(401);
            assertThat(client.get("/api/health").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void administratorSurvivesActualApplicationRestartAndSecureCookieSettingWorks() throws Exception {
        int restartPort;
        try (var socket = new ServerSocket(0)) { restartPort = socket.getLocalPort(); }
        try (var application = new RestartTestApplication("auth-restart-");
                ApiClient client = new ApiClient(restartPort)) {
            Process child = startApplication(restartPort, false, application);
            awaitApplication(client, child);
            String token = client.csrf();
            var created = client.setup("restartdoctor", PASSWORD, token);
            assertThat(created.statusCode()).isEqualTo(201);
            long id = json.readTree(created.body()).get("id").asLong();
            assertThat(client.login("restartdoctor", PASSWORD, token).statusCode()).isEqualTo(200);
            String oldSession = client.sessionId();
            application.stop();
            child = startApplication(restartPort, false, application);
            awaitApplication(client, child);
            assertThat(client.rawCookieGet("/api/auth/me", "JSESSIONID=" + oldSession).statusCode()).isEqualTo(401);
            assertThat(json.readTree(client.get("/api/auth/setup-status").body()).get("initialized").asBoolean()).isTrue();
            assertThat(client.login("restartdoctor", PASSWORD, client.csrf()).statusCode()).isEqualTo(200);
            assertThat(json.readTree(client.get("/api/auth/me").body()).get("id").asLong()).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
            application.stop();
            child = startApplication(restartPort, true, application);
            try (ApiClient secureClient = new ApiClient(restartPort)) {
                awaitApplication(secureClient, child);
                assertThat(secureClient.get("/api/auth/csrf").headers().firstValue("Set-Cookie").orElseThrow())
                        .contains("Secure", "HttpOnly", "SameSite=Lax");
            }
        }
    }

    private Process startApplication(int applicationPort, boolean secure, RestartTestApplication application) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", Path.of("target/medical-rag-backend-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString());
        builder.environment().putAll(Map.of(
                "SERVER_PORT", String.valueOf(applicationPort), "DB_HOST", DATABASE.getHost(),
                "DB_PORT", String.valueOf(DATABASE.getFirstMappedPort()), "DB_NAME", DATABASE.getDatabaseName(),
                "DB_USERNAME", DATABASE.getUsername(), "DB_PASSWORD", DATABASE.getPassword(),
                "SESSION_COOKIE_SECURE", String.valueOf(secure)));
        return application.start(builder);
    }

    private void awaitApplication(ApiClient client, Process application) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            assertThat(application.isAlive()).as("Restart test application must start").isTrue();
            try {
                if (client.get("/api/health").statusCode() == 200) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Restart test application did not become ready");
    }

    class ApiClient implements AutoCloseable {
        private final String base;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(2)).build();

        ApiClient(int serverPort) { base = "http://127.0.0.1:" + serverPort; }
        HttpResponse<String> get(String path) throws Exception { return send(HttpRequest.newBuilder(URI.create(base + path)).GET()); }
        HttpResponse<String> rawCookieGet(String path, String cookie) throws Exception {
            try (HttpClient raw = HttpClient.newHttpClient()) {
                return raw.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie).GET()
                        .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            }
        }
        HttpResponse<String> basicGet(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Basic dGVzdDp0ZXN0").GET());
        }
        HttpResponse<String> post(String path, String body, String contentType, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return send(request);
        }
        HttpResponse<String> setup(String username, String password, String token) throws Exception {
            return post("/api/auth/setup", json.writeValueAsString(Map.of("username", username, "password", password)), "application/json", token);
        }
        HttpResponse<String> login(String username, String password, String token) throws Exception {
            return post("/api/auth/login", "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), "application/x-www-form-urlencoded", token);
        }
        String csrf() throws Exception {
            var response = get("/api/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(response.body());
            assertThat(body.get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
            return body.get("token").asText();
        }
        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .findFirst().orElseThrow().getValue();
        }
        HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            return http.send(builder.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { http.close(); }
    }

    // Test context only; verifies default protection of private read/write endpoints.
    @RestController
    static class PrivateProbe {
        @RequestMapping(path = "/api/test/private", method = {RequestMethod.GET, RequestMethod.POST})
        Map<String, Boolean> probe() { return Map.of("accepted", true); }
    }
}
