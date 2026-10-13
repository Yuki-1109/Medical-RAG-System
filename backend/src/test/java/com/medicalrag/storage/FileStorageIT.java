package com.medicalrag.storage;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.medicalrag.knowledgebase.KnowledgeBaseService;
import com.medicalrag.knowledgebase.KnowledgeBaseException;
import com.medicalrag.testutil.RestartTestApplication;
import com.medicalrag.testutil.TestStorageRoot;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileStorageIT {
    static final TestStorageRoot STORAGE = TestStorageRoot.create();
    @AfterAll static void releaseStorage() throws Exception { STORAGE.close(); }
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        STORAGE.configure(registry);
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired KnowledgeBaseFileStorage files;
    @Autowired LocalOriginalStore raw;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;
    long owner, k1, k2;
    @BeforeEach void fixture() {
        // This SQL runs only in the Testcontainers database, never the existing administrator.
        jdbc.update("DELETE FROM knowledge_bases"); jdbc.update("DELETE FROM users");
        owner = jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('storagedoctor',?) RETURNING id", Long.class, passwords.encode("A5-Isolated-Test-Password-2026"));
        k1 = jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name) VALUES (?, 'A5 K1') RETURNING id", Long.class, owner);
        k2 = jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name) VALUES (?, 'A5 K2') RETURNING id", Long.class, owner);
    }
    StoredOriginal save() throws Exception {
        try (var input = new ByteArrayInputStream(OriginalTestFiles.pdf())) { return files.save(Long.toString(k1), "合成空白PDF.pdf", OriginalType.PDF, input); }
    }
    @Test @WithMockUser(username = "storagedoctor", roles = "ADMIN")
    void internalFacadeRechecksRealOwnershipAndDoesNotCreateBusinessRecords() throws Exception {
        var receipt = save(); var output = new ByteArrayOutputStream(); files.copyTo(Long.toString(k1), receipt, output);
        assertThat(output.toByteArray()).isEqualTo(OriginalTestFiles.pdf());
        assertThatThrownBy(() -> files.copyTo(Long.toString(k2), receipt, OutputStream.nullOutputStream())).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> files.delete(Long.toString(k2), receipt)).isInstanceOf(StorageException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)=0 FROM medical_images", Boolean.class)).isTrue();
        assertThat(knowledgeBases.detail(k1, owner).documentCount()).isZero();
        assertThat(knowledgeBases.detail(k1, owner).imageCount()).isZero();
        files.delete(Long.toString(k1), receipt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_bases", Integer.class)).isEqualTo(2);
    }
    @Test void unauthenticatedInternalCallsCannotReadWriteOrDelete() throws Exception {
        var receipt = raw.save(new StorageScope(k1), "合成.pdf", OriginalType.PDF, new ByteArrayInputStream(OriginalTestFiles.pdf()));
        assertThatThrownBy(this::save).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> files.copyTo(Long.toString(k1), receipt, OutputStream.nullOutputStream())).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> files.delete(Long.toString(k1), receipt)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        var output = new ByteArrayOutputStream(); raw.copyTo(new StorageScope(k1), receipt, output);
        assertThat(output.toByteArray()).isEqualTo(OriginalTestFiles.pdf());
    }
    @Test @WithMockUser(username = "storagedoctor", roles = "VIEWER")
    void nonAdministratorInternalWritesAreRejected() {
        assertThatThrownBy(this::save).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test @Transactional @WithMockUser(username = "storagedoctor", roles = "ADMIN")
    void foreignKnowledgeBaseIsDeniedBeforeAnyFileOperation() throws Exception {
        // Constraint relaxation exists only in this rolled-back isolated fixture transaction.
        jdbc.execute("ALTER TABLE users DROP CONSTRAINT users_single_administrator");
        long other = jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('foreignstoragefixture',?) RETURNING id", Long.class, passwords.encode("A5-Isolated-Foreign-Password-2026"));
        long foreign = jdbc.queryForObject("INSERT INTO knowledge_bases (owner_id,name) VALUES (?, 'PRIVATE-A5-FIXTURE') RETURNING id", Long.class, other);
        var receipt = raw.save(new StorageScope(foreign), "foreign.pdf", OriginalType.PDF, new ByteArrayInputStream(OriginalTestFiles.pdf()));
        assertThatThrownBy(() -> files.save(Long.toString(foreign), "x.pdf", OriginalType.PDF, new ByteArrayInputStream(OriginalTestFiles.pdf()))).isInstanceOf(KnowledgeBaseException.class).hasMessageNotContaining("PRIVATE-A5");
        assertThatThrownBy(() -> files.copyTo(Long.toString(foreign), receipt, OutputStream.nullOutputStream())).isInstanceOf(KnowledgeBaseException.class);
        assertThatThrownBy(() -> files.delete(Long.toString(foreign), receipt)).isInstanceOf(KnowledgeBaseException.class);
        var output = new ByteArrayOutputStream(); raw.copyTo(new StorageScope(foreign), receipt, output);
        assertThat(output.toByteArray()).isEqualTo(OriginalTestFiles.pdf());
    }
    @Test @WithMockUser(username = "storagedoctor", roles = "ADMIN")
    void storedOriginalsHaveNoPublicOrAuthenticatedStaticHttpDownloadPath() throws Exception {
        var receipt = save();
        for (String prefix : new String[] {"/uploads/", "/data/originals/.storage-objects/", "/.storage-objects/"}) {
            String path = prefix + receipt.storageKey();
            mvc.perform(get(path).with(user("storagedoctor").roles("ADMIN"))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/storage/upload").with(user("storagedoctor").roles("ADMIN"))).andExpect(status().isNotFound());
        // Explicit anonymous request still receives 401 even though this test method is authenticated.
        mvc.perform(get("/uploads/" + receipt.storageKey()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(username = "storagedoctor", roles = "ADMIN")
    void separateSpringBootProcessesReadThePersistedOriginalAfterStartupAndRestart() throws Exception {
        var receipt = save();
        try (var application = new RestartTestApplication("a5-storage-restart-")) {
            for (int restart = 0; restart < 2; restart++) {
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), StorageRestartProbe.class.getName(),
                        Long.toString(k1), receipt.storageKey(), Long.toString(receipt.size()), receipt.sha256(), receipt.type().name());
                builder.environment().putAll(Map.of("APP_STORAGE_ROOT", STORAGE.value(), "SPRING_DATASOURCE_URL", DATABASE.getJdbcUrl(),
                        "SPRING_DATASOURCE_USERNAME", DATABASE.getUsername(), "SPRING_DATASOURCE_PASSWORD", DATABASE.getPassword()));
                STORAGE.configureProcess(builder);
                Process child = application.start(builder);
                assertThat(child.waitFor(30, TimeUnit.SECONDS)).as("Independent Spring Boot storage probe must finish").isTrue();
                assertThat(child.exitValue()).isZero(); application.stop();
                assertThat(Files.readString(application.logPath())).contains("A5_RESTART_READ_OK");
            }
        }
        var output = new ByteArrayOutputStream(); files.copyTo(Long.toString(k1), receipt, output);
        assertThat(output.toByteArray()).isEqualTo(OriginalTestFiles.pdf());
    }
}
