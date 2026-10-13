package com.medicalrag.knowledgebase;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeBaseIT {
    // Test-only credentials and disposable databases, never the real Windows administrator.
    static final String PASSWORD = "A4-Isolated-Integration-Password";
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;
    @LocalServerPort int port;

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM knowledge_bases");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username,password_hash) VALUES ('doctor',?)", passwords.encode(PASSWORD));
    }

    Client signedIn(int serverPort) throws Exception {
        Client client = new Client(serverPort);
        assertThat(client.send("POST", "/api/auth/login", "username=doctor&password=" + PASSWORD, "application/x-www-form-urlencoded", client.csrf()).statusCode()).isEqualTo(200);
        return client;
    }
    String input(String name, String description) throws Exception { return json.writeValueAsString(Map.of("name", name, "description", description)); }
    JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    String create(Client client, String name) throws Exception {
        var response = client.write("POST", "/api/knowledge-bases", input(name, "研究资料"));
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response).get("id").asText();
    }

    @Test void crudIsRealAndUpdatingOrDeletingOneLibraryNeverChangesAnother() throws Exception {
        try (Client client = signedIn(port)) {
            assertThat(body(client.get("/api/knowledge-bases"))).isEmpty();
            String k1 = create(client, "K1"), k2 = create(client, "K2");
            assertThat(k1).isNotEqualTo(k2);
            var updated = client.write("PATCH", "/api/knowledge-bases/" + k1, input("K1 更名", "新的简介"));
            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(body(updated).get("description").asText()).isEqualTo("新的简介");
            assertThat(body(updated).get("documentCount").asInt()).isZero();
            assertThat(body(updated).get("imageCount").asInt()).isZero();
            assertThat(body(updated).get("demo").asBoolean()).isFalse();
            assertThat(body(updated).get("id").isTextual()).isTrue();
            assertThat(updated.body()).doesNotContain("ownerId", "password", "hash");
            assertThat(body(client.get("/api/knowledge-bases/" + k2)).get("name").asText()).isEqualTo("K2");
            assertThat(jdbc.queryForObject("SELECT name FROM knowledge_bases WHERE id = ?", String.class, Long.valueOf(k1))).isEqualTo("K1 更名");
            assertThat(jdbc.queryForObject("SELECT owner_id = (SELECT id FROM users WHERE username = 'doctor') FROM knowledge_bases WHERE id = ?", Boolean.class, Long.valueOf(k1))).isTrue();
            assertThat(client.write("DELETE", "/api/knowledge-bases/" + k1, null).statusCode()).isEqualTo(204);
            assertThat(client.get("/api/knowledge-bases/" + k1).statusCode()).isEqualTo(404);
            assertThat(body(client.get("/api/knowledge-bases"))).hasSize(1);
            assertThat(body(client.get("/api/knowledge-bases/" + k2)).get("name").asText()).isEqualTo("K2");
        }
    }

    @Test void anonymousCrudIsDeniedEvenWithValidAnonymousCsrf() throws Exception {
        try (Client client = new Client(port)) {
            for (String path : new String[] {"/api/knowledge-bases", "/api/knowledge-bases/1"}) assertThat(client.get(path).statusCode()).isEqualTo(401);
            for (String method : new String[] {"POST", "PATCH", "DELETE"}) {
                assertThat(client.write(method, "/api/knowledge-bases" + (method.equals("POST") ? "" : "/1"), input("禁止", "" )).statusCode()).isEqualTo(401);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_bases", Integer.class)).isZero();
        }
    }

    @Test void allWriteMethodsRequireCurrentCsrf() throws Exception {
        try (Client client = signedIn(port)) {
            String id = create(client, "CSRF 库");
            for (String method : new String[] {"POST", "PATCH", "DELETE"}) {
                for (String token : new String[] {null, "bad-token"}) {
                    var response = client.send(method, "/api/knowledge-bases" + (method.equals("POST") ? "" : "/" + id), input("不可写", ""), "application/json", token);
                    assertThat(response.statusCode()).isEqualTo(403);
                    assertThat(response.body()).contains("CSRF_INVALID");
                }
            }
            assertThat(body(client.get("/api/knowledge-bases/" + id)).get("name").asText()).isEqualTo("CSRF 库");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\u00a0\u3000", "x\nX"})
    void invalidNamesAreRejectedWithoutChangingExistingLibrary(String name) throws Exception {
        try (Client client = signedIn(port)) {
            String id = create(client, "保留");
            assertThat(client.write("POST", "/api/knowledge-bases", input(name, "")).statusCode()).isEqualTo(400);
            assertThat(client.write("PATCH", "/api/knowledge-bases/" + id, input(name, "")).statusCode()).isEqualTo(400);
            assertThat(body(client.get("/api/knowledge-bases/" + id)).get("name").asText()).isEqualTo("保留");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "0", "01", "abc", "1.5", "9223372036854775808"})
    void invalidIdsAre400AndMissingIdsAre404(String id) throws Exception {
        try (Client client = signedIn(port)) {
            for (String method : new String[] {"GET", "PATCH", "DELETE"}) {
                assertThat(client.send(method, "/api/knowledge-bases/" + id, method.equals("PATCH") ? input("有效", "") : null, "application/json", client.csrf()).statusCode()).isEqualTo(400);
            }
            var missing = client.get("/api/knowledge-bases/9223372036854775807");
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(missing.body()).contains("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问").doesNotContain("jdbc:", "SELECT", "stackTrace");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{\"name\":23}", "{\"name\":\"库\",\"description\":false}", "{\"name\":\"库\",\"owner_id\":1}", "{\"name\":\"库\",\"id\":1}", "{\"name\":\"库\",\"demo\":true}"})
    void invalidBodiesAndOwnershipInjectionCannotCreateOrUpdate(String input) throws Exception {
        try (Client client = signedIn(port)) {
            String id = create(client, "原名");
            assertThat(client.write("POST", "/api/knowledge-bases", input).statusCode()).isEqualTo(400);
            assertThat(client.write("PATCH", "/api/knowledge-bases/" + id, input).statusCode()).isEqualTo(400);
            assertThat(body(client.get("/api/knowledge-bases/" + id)).get("name").asText()).isEqualTo("原名");
            assertThat(body(client.get("/api/knowledge-bases"))).hasSize(1);
        }
    }

    @Test void lengthsAndOptionalDescriptionAreEnforced() throws Exception {
        try (Client client = signedIn(port)) {
            assertThat(client.write("POST", "/api/knowledge-bases", input("x".repeat(101), "")).statusCode()).isEqualTo(400);
            assertThat(client.write("POST", "/api/knowledge-bases", input("有效", "x".repeat(2001))).statusCode()).isEqualTo(400);
            assertThat(client.write("POST", "/api/knowledge-bases", "{\"name\":\"有效\"}").statusCode()).isEqualTo(201);
        }
    }

    @Test @Transactional @WithMockUser(username = "doctor", roles = "ADMIN")
    void foreignOwnerFixturesAreNeverVisibleAndSingletonRelaxationIsRolledBack() throws Exception {
        // ONLY this disposable test transaction relaxes the singleton constraint; rollback
        // restores it. Product migrations/registration never allow a second administrator.
        jdbc.execute("ALTER TABLE users DROP CONSTRAINT users_single_administrator");
        long other = jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('foreignfixture',?) RETURNING id", Long.class, passwords.encode(PASSWORD));
        long foreign = jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name,description) VALUES (?, 'PRIVATE-FIXTURE-NAME','PRIVATE-FIXTURE-DESCRIPTION') RETURNING id", Long.class, other);
        mvc.perform(get("/api/knowledge-bases")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/knowledge-bases/" + foreign).param("owner_id", Long.toString(other))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                patch("/api/knowledge-bases/" + foreign).content(input("窃取", "" )).contentType("application/json"), delete("/api/knowledge-bases/" + foreign)}) {
            String response = mvc.perform(request.with(csrf())).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("PRIVATE-FIXTURE", "foreignfixture", "owner_id", "ownerId");
        }
        assertThat(jdbc.queryForObject("SELECT name FROM knowledge_bases WHERE id = ?", String.class, foreign)).isEqualTo("PRIVATE-FIXTURE-NAME");
        mvc.perform(post("/api/knowledge-bases").with(csrf()).content(input("自己的库", "")).contentType("application/json"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/knowledge-bases")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("自己的库"));
    }

    @Test @Transactional @WithMockUser(username = "doctor", roles = "ADMIN")
    void futureMaterialSchemaFailsClosedAndOwnerDeletionIsRestricted() throws Exception {
        long owner = jdbc.queryForObject("SELECT id FROM users", Long.class);
        long id = jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name) VALUES (?, '保留') RETURNING id", Long.class, owner);
        jdbc.execute("CREATE TABLE documents (id BIGINT PRIMARY KEY, knowledge_base_id BIGINT REFERENCES knowledge_bases(id) ON DELETE RESTRICT)");
        jdbc.update("INSERT INTO documents VALUES (1, ?)", id);
        mvc.perform(delete("/api/knowledge-bases/" + id).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/knowledge-bases/" + id)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_bases", Integer.class)).isEqualTo(1);
    }

    @Test void foreignKeyAndDemoMarkerAreRealDatabaseConstraints() throws Exception {
        try (Client client = signedIn(port)) {
            String id = create(client, "演示标记");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM users")).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO knowledge_bases (owner_id,name) VALUES (-1,'孤儿')")).isInstanceOf(DataAccessException.class);
            jdbc.update("UPDATE knowledge_bases SET is_demo = true WHERE id = ?", Long.valueOf(id));
            assertThat(body(client.get("/api/knowledge-bases/" + id)).get("demo").asBoolean()).isTrue();
        }
    }

    @Test void librariesAndAdministratorSurviveActualJarRestartWithoutRepeatingMigration() throws Exception {
        int restartPort;
        try (var socket = new ServerSocket(0)) { restartPort = socket.getLocalPort(); }
        Path log = Files.createTempFile(Path.of("target"), "knowledge-base-restart-", ".log");
        Process application = null;
        String k1, k2;
        try {
            application = startApplication(restartPort, log);
            awaitApplication(restartPort, application);
            try (Client client = signedIn(restartPort)) {
                k1 = create(client, "K1 重启"); k2 = create(client, "K2 重启");
                assertThat(client.write("PATCH", "/api/knowledge-bases/" + k1, input("重启后仍在", "持久化简介")).statusCode()).isEqualTo(200);
            }
            stopApplication(application);
            application = startApplication(restartPort, log);
            awaitApplication(restartPort, application);
            try (Client client = new Client(restartPort)) { assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401); }
            try (Client client = signedIn(restartPort)) {
                assertThat(body(client.get("/api/knowledge-bases/" + k1)).get("name").asText()).isEqualTo("重启后仍在");
                assertThat(body(client.get("/api/knowledge-bases/" + k1)).get("description").asText()).isEqualTo("持久化简介");
                assertThat(body(client.get("/api/knowledge-bases/" + k2)).get("name").asText()).isEqualTo("K2 重启");
                assertThat(body(client.get("/api/auth/setup-status")).get("initialized").asBoolean()).isTrue();
                assertThat(body(client.get("/api/knowledge-bases"))).hasSize(2);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='3' AND success", Integer.class)).isEqualTo(1);
        } finally { if (application != null) stopApplication(application); Files.deleteIfExists(log); }
    }
    Process startApplication(int serverPort, Path log) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", Path.of("target/medical-rag-backend-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString());
        builder.environment().putAll(Map.of("SERVER_PORT", Integer.toString(serverPort), "SPRING_DATASOURCE_URL", DATABASE.getJdbcUrl(),
                "SPRING_DATASOURCE_USERNAME", DATABASE.getUsername(), "SPRING_DATASOURCE_PASSWORD", DATABASE.getPassword(), "SESSION_COOKIE_SECURE", "false"));
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }
    void awaitApplication(int serverPort, Process application) throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(application.isAlive()).isTrue();
            try (Client client = new Client(serverPort)) { if (client.get("/api/health").statusCode() == 200) return; }
            catch (java.io.IOException ignored) { }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Isolated restart application did not become ready");
    }
    void stopApplication(Process application) throws Exception {
        application.destroy();
        if (!application.waitFor(10, TimeUnit.SECONDS)) { application.destroyForcibly(); assertThat(application.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
    }
    class Client implements AutoCloseable {
        final String base;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(2)).build();
        Client(int serverPort) { base = "http://127.0.0.1:" + serverPort; }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, "application/json", null); }
        String csrf() throws Exception { return body(get("/api/auth/csrf")).get("token").asText(); }
        HttpResponse<String> write(String method, String path, String body) throws Exception { return send(method, path, body, "application/json", csrf()); }
        HttpResponse<String> send(String method, String path, String body, String contentType, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { http.close(); }
    }
}
