package com.medicalrag.knowledgebase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeBaseMigrationIT {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @Test void upgradingAnExistingV2DatabaseKeepsAccountHashIdentityAndMigrationHistory() {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("2").load().migrate();
        String hash = new BCryptPasswordEncoder(12).encode("Isolated-Migration-Password-2026");
        long id = jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('mianmian',?) RETURNING id", Long.class, hash);
        var migration = Flyway.configure().dataSource(source).cleanDisabled(true).load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT id FROM users", Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT username FROM users", String.class)).isEqualTo("mianmian");
        assertThat(new BCryptPasswordEncoder().matches("Isolated-Migration-Password-2026", hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class)).isEqualTo("0.8.2");
        jdbc.update("INSERT INTO knowledge_bases (owner_id,name) VALUES (?, '增量升级库')", id);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2','3') AND success", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_bases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='knowledge_bases'::regclass AND contype='f'", String.class)).isEqualTo("r");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (username,password_hash) VALUES ('second',?)", hash)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
