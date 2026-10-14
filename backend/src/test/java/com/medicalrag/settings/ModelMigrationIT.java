package com.medicalrag.settings;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.medicalrag.testutil.TestStorageRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers class ModelMigrationIT {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @Test void upgradesV5ToV6WithoutChangingAccountsLibrariesPdfImagesChecksumsOrOriginals() throws Exception {
        var source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("5").load().migrate();
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        long owner=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('mianmian',?) RETURNING id",Long.class,new BCryptPasswordEncoder(4).encode("B1-Synthetic-Migration-Password"));
        long base=jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name,description) VALUES (?,'保留旧库','旧简介') RETURNING id",Long.class,owner);
        byte[] pdf=com.medicalrag.document.SyntheticPdfs.text(1), png=com.medicalrag.image.SyntheticImages.png();
        String docKey="documents/"+base+"/"+UUID.randomUUID()+"/original.pdf", imageKey="images/"+base+"/"+UUID.randomUUID()+"/original.png";
        try(var root=TestStorageRoot.create()) {
            Path doc=root.path().resolve(".storage-objects/"+docKey),image=root.path().resolve(".storage-objects/"+imageKey);Files.createDirectories(doc.getParent());Files.createDirectories(image.getParent());Files.write(doc,pdf);Files.write(image,png);
            jdbc.update("INSERT INTO documents (knowledge_base_id,original_filename,storage_key,file_size,sha256,page_count,lifecycle_status) VALUES (?,'Synthetic.pdf',?,?,?,1,'ACTIVE')",base,docKey,pdf.length,hash(pdf));
            jdbc.update("INSERT INTO medical_images (knowledge_base_id,original_filename,storage_key,file_format,file_size,sha256,width,height,image_type,note,lifecycle_status) VALUES (?,'Synthetic.png',?,'PNG',?,?,32,24,'LESION','原人工备注','ACTIVE')",base,imageKey,png.length,hash(png));
            Map<String,List<Map<String,Object>>> before=new HashMap<>();for(String table:List.of("users","knowledge_bases","documents","medical_images"))before.put(table,jdbc.queryForList("SELECT * FROM "+table));
            var flyway=Flyway.configure().dataSource(source).cleanDisabled(true).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
            for(String table:before.keySet())assertThat(jdbc.queryForList("SELECT * FROM "+table)).isEqualTo(before.get(table));
            assertThat(Files.readAllBytes(doc)).isEqualTo(pdf);assertThat(Files.readAllBytes(image)).isEqualTo(png);
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'6' ORDER BY installed_rank")).isEqualTo(history);assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM model_configs",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='model_configs'::regclass AND contype='f'",String.class)).isEqualTo("r");
        }
    }
    String hash(byte[] data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
}
