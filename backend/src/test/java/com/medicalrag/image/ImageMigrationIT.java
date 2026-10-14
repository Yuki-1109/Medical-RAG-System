package com.medicalrag.image;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import com.medicalrag.testutil.TestStorageRoot;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import static org.assertj.core.api.Assertions.*;
@Testcontainers class ImageMigrationIT {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @Test void upgradeV4PreservesAdministratorPdfLibraryChecksumsAndOriginalBytesAndDoesNotRepeat() throws Exception {
        var source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("4").load().migrate();
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        String passwordHash=new BCryptPasswordEncoder(12).encode("A7-Synthetic-Migration-Password");
        long owner=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('mianmian',?) RETURNING id",Long.class,passwordHash);
        long base=jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name,description) VALUES (?, '保留旧库','原简介') RETURNING id",Long.class,owner);
        byte[] pdf=com.medicalrag.document.SyntheticPdfs.text(1);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        String key="documents/"+base+"/"+UUID.randomUUID()+"/original.pdf";
        try(var root=TestStorageRoot.create()) {
            Path file=root.path().resolve(".storage-objects").resolve(key);Files.createDirectories(file.getParent());Files.write(file,pdf);
            jdbc.update("INSERT INTO documents (knowledge_base_id,original_filename,storage_key,file_size,sha256,page_count,lifecycle_status) VALUES (?,?,?,?,?,1,'ACTIVE')",base,"Synthetic English.pdf",key,pdf.length,hash);
            var document=jdbc.queryForMap("SELECT * FROM documents");var account=jdbc.queryForMap("SELECT * FROM users");var library=jdbc.queryForMap("SELECT * FROM knowledge_bases");
            var upgraded=Flyway.configure().dataSource(source).target("5").cleanDisabled(true).load();assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("5");
            assertThat(jdbc.queryForMap("SELECT * FROM documents")).isEqualTo(document);assertThat(jdbc.queryForMap("SELECT * FROM users")).isEqualTo(account);assertThat(jdbc.queryForMap("SELECT * FROM knowledge_bases")).isEqualTo(library);
            assertThat(Files.readAllBytes(file)).isEqualTo(pdf);assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'5' ORDER BY installed_rank")).isEqualTo(history);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM medical_images",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='medical_images'::regclass AND contype='f'",String.class)).isEqualTo("r");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO medical_images (knowledge_base_id,original_filename,storage_key,file_format) VALUES (?, 'x.png',?, 'PNG')",base,"images/999/"+UUID.randomUUID()+"/original.png")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
