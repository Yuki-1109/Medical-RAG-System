package com.medicalrag.document;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DocumentMigrationIT {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @Test void v3UpgradeKeepsExistingAccountKnowledgeBasesAndAllHistoricalChecksums() {
        var source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()); var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("3").load().migrate();
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        String hash=new BCryptPasswordEncoder(12).encode("Synthetic-Migration-Password-2026");
        long owner=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('mianmian',?) RETURNING id",Long.class,hash);
        long base=jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name,description) VALUES (?, '原有研究库','保留简介') RETURNING id",Long.class,owner);
        var flyway=Flyway.configure().dataSource(source).cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'4' ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,owner)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT description FROM knowledge_bases WHERE id=?",String.class,base)).isEqualTo("保留简介");
        assertThat(jdbc.queryForObject("SELECT deletion_pending FROM knowledge_bases WHERE id=?",Boolean.class,base)).isFalse();
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class)).isEqualTo("0.8.2");
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='documents'::regclass AND contype='f'",String.class)).isEqualTo("r");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (username,password_hash) VALUES ('second',?)",hash)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
