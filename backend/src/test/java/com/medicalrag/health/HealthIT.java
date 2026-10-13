package com.medicalrag.health;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import com.medicalrag.testutil.TestStorageRoot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIT {
    private static final TestStorageRoot STORAGE = TestStorageRoot.create();
    @AfterAll static void releaseStorage() throws Exception { STORAGE.close(); }
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        STORAGE.configure(registry);
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void healthUsesRealPostgresVectorOperationAndMigrationRecords() {
        var response = http.getForEntity("/api/health", HealthResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        HealthResponse health = response.getBody();
        assertThat(health).isNotNull();
        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.database().status()).isEqualTo("UP");
        assertThat(health.pgvector().version()).isEqualTo("0.8.2");
        assertThat(health.migrations().version()).isEqualTo("3");
    }

    @Test
    void unknownPrivateApiRequiresLoginBeforeRouting() {
        var response = http.getForEntity("/api/not-implemented", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("AUTHENTICATION_REQUIRED", "请先登录").doesNotContain("stackTrace", "jdbc:");
    }

    @Test
    void runningMigrationsAgainDoesNotCreateDuplicateHistory() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingVectorExtensionReturns503WhileDatabaseRemainsAvailable() {
        jdbc.execute("DROP EXTENSION vector");
        try {
            var response = http.getForEntity("/api/health", HealthResponse.class);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().database().status()).isEqualTo("UP");
            assertThat(response.getBody().pgvector().status()).isEqualTo("DOWN");
        } finally {
            jdbc.execute("CREATE EXTENSION vector");
        }
    }

    @Test
    void missingMigrationHistoryIsNotReportedAsHealthy() {
        jdbc.execute("ALTER TABLE flyway_schema_history RENAME TO temporarily_hidden_history");
        try {
            var response = http.getForEntity("/api/health", HealthResponse.class);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().migrations().status()).isEqualTo("DOWN");
        } finally {
            jdbc.execute("ALTER TABLE temporarily_hidden_history RENAME TO flyway_schema_history");
        }
    }
}
