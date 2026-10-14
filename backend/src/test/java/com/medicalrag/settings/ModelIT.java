package com.medicalrag.settings;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.net.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalrag.testutil.TestStorageRoot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.medicalrag.settings.ModelTypes.*;

@Testcontainers @AutoConfigureMockMvc @SpringBootTest
@WithMockUser(username="modeldoctor",roles="ADMIN") @ExtendWith(OutputCaptureExtension.class)
class ModelIT {
    static final TestStorageRoot STORAGE=TestStorageRoot.create();
    static final Path KEYS=temporaryKeys();
    static Path temporaryKeys() { try { return Files.createTempDirectory("b1-model-keys-"); } catch(java.io.IOException e) {throw new java.io.UncheckedIOException(e);} }
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        STORAGE.configure(p);p.add("app.models.key-file",()->KEYS.resolve("secrets/master.key").toString());p.add("app.models.allow-loopback-http",()->true);p.add("app.models.timeout-millis",()->1000);
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);p.add("spring.datasource.username",DATABASE::getUsername);p.add("spring.datasource.password",DATABASE::getPassword);
    }
    @AfterAll static void cleanup() throws Exception {STORAGE.close();try(var paths=Files.walk(KEYS)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ModelService models;@Autowired ModelRepository repository;@Autowired ModelKeyStore keys;@Autowired ModelProperties properties;@Autowired com.medicalrag.storage.StorageProperties storage;
    long owner;HttpServer server;AtomicInteger count=new AtomicInteger(), code=new AtomicInteger(200);AtomicReference<String> response=new AtomicReference<>("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
    @BeforeEach void fixture() throws Exception {
        jdbc.update("DELETE FROM model_configs");jdbc.update("DELETE FROM users");
        owner=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('modeldoctor',?) RETURNING id",Long.class,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("B1-Synthetic-Test-Password"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.createContext("/",exchange->{count.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] bytes=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(code.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
    }
    @AfterEach void close() {server.stop(0);}
    Input input(Type type,String key) {return new Input(type.name(),"OPENAI_COMPATIBLE","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","model-one",key,false);}
    String body(Type type,String key) throws Exception {return json.writeValueAsString(input(type,key));}
    @Test void newV1ThroughV6SchemaHasTwoUnconfiguredIndependentSlots() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(6);
        mvc.perform(get("/api/settings/models")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].configured").value(false));
    }
    @Test void savesEncryptedKeysIndependentlyAndNeverReturnsSecretsOrCallsOnSave(CapturedOutput output) throws Exception {
        for(Type type:Type.values()) mvc.perform(put("/api/settings/models").with(csrf().asHeader()).contentType("application/json").content(body(type,"B1-SYNTHETIC-ONLY"))).andExpect(status().isOk()).andExpect(jsonPath("$.keyConfigured").value(true)).andExpect(jsonPath("$.testStatus").value("UNTESTED"));
        assertThat(count.get()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM model_configs",Integer.class)).isEqualTo(2);
        for(var row:jdbc.queryForList("SELECT * FROM model_configs")) assertThat(row.toString()).doesNotContain("B1-SYNTHETIC-ONLY");
        String result=mvc.perform(get("/api/settings/models")).andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("B1-SYNTHETIC-ONLY","encrypted_api_key","cipher","apiKey","master.key");assertThat(output.getAll()).doesNotContain("B1-SYNTHETIC-ONLY","Authorization: Bearer");
    }
    @Test void blankPreservesExistingKeyAndModelUpdateClearsOldSuccess() throws Exception {
        models.save(owner,input(Type.CHAT,"synthetic-original"));models.test(owner,new TestInput("CHAT"));String cipher=repository.find(owner,Type.CHAT).orElseThrow().cipher();
        var updated=new Input("CHAT","OPENAI_COMPATIBLE",input(Type.CHAT,"").baseUrl(),"model-two","",false);
        var info=models.save(owner,updated);assertThat(info.testStatus()).isEqualTo("UNTESTED");assertThat(info.testedAt()).isNull();assertThat(info.embeddingDimension()).isNull();assertThat(repository.find(owner,Type.CHAT).orElseThrow().cipher()).isEqualTo(cipher);
        assertThat(new ModelKeyStore(properties,storage,repository).decrypt(cipher,owner,Type.CHAT)).isEqualTo("synthetic-original");
    }
    @Test void explicitDeletionDoesNotClearOtherTypeAndNoKeyCannotBeTested() throws Exception {
        models.save(owner,input(Type.CHAT,"one"));models.save(owner,input(Type.EMBEDDING,"two"));
        var info=models.save(owner,new Input("CHAT","OPENAI_COMPATIBLE",input(Type.CHAT,"").baseUrl(),"model-one","",true));assertThat(info.keyConfigured()).isFalse();assertThat(models.list(owner).get(1).keyConfigured()).isTrue();
        mvc.perform(post("/api/settings/models/test").with(csrf().asHeader()).contentType("application/json").content("{\"type\":\"CHAT\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));assertThat(count.get()).isZero();
    }
    @Test void successAndFailurePersistAndNeverAffectOtherModelOrFileBusiness() throws Exception {
        models.save(owner,input(Type.CHAT,"one"));models.save(owner,input(Type.EMBEDDING,"two"));
        models.test(owner,new TestInput("CHAT"));response.set("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");assertThat(models.test(owner,new TestInput("EMBEDDING")).embeddingDimension()).isEqualTo(2);
        code.set(401);mvc.perform(post("/api/settings/models/test").with(csrf().asHeader()).contentType("application/json").content("{\"type\":\"CHAT\"}")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_AUTH_FAILED"));
        var list=models.list(owner);assertThat(list.get(0).testStatus()).isEqualTo("FAILED");assertThat(list.get(1).testStatus()).isEqualTo("SUCCESS");assertThat(list.get(1).embeddingDimension()).isEqualTo(2);
        mvc.perform(get("/api/knowledge-bases")).andExpect(status().isOk());mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
    @Test void encryptedRowsBlockRegenerationOfMissingMasterAndReadStillWorks() throws Exception {
        models.save(owner,input(Type.CHAT,"original"));byte[] master=Files.readAllBytes(keys.path());Files.delete(keys.path());
        try {
            mvc.perform(post("/api/settings/models/test").with(csrf().asHeader()).contentType("application/json").content("{\"type\":\"CHAT\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_KEY_UNAVAILABLE"));
            mvc.perform(put("/api/settings/models").with(csrf().asHeader()).contentType("application/json").content(body(Type.CHAT,"replacement"))).andExpect(status().isServiceUnavailable());
            assertThat(keys.path()).doesNotExist();mvc.perform(get("/api/settings/models")).andExpect(status().isOk());mvc.perform(get("/api/knowledge-bases")).andExpect(status().isOk());
        } finally {Files.write(keys.path(),master,StandardOpenOption.CREATE_NEW);if(Files.getFileStore(keys.path()).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(keys.path(),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
    }
    @Test void corruptCipherIsSafelyReportedWithoutProviderCallOrSecretInLogs(CapturedOutput output) throws Exception {
        models.save(owner,input(Type.CHAT,"B1-SYNTHETIC-ONLY"));jdbc.update("UPDATE model_configs SET encrypted_api_key='invalid-secret-cipher'");
        String result=mvc.perform(post("/api/settings/models/test").with(csrf().asHeader()).contentType("application/json").content("{\"type\":\"CHAT\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_DECRYPT_FAILED")).andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("invalid-secret-cipher","B1-SYNTHETIC-ONLY");assertThat(output.getAll()).doesNotContain("B1-SYNTHETIC-ONLY","invalid-secret-cipher");assertThat(count.get()).isZero();
    }
    @Test void anonymousReadSaveAndTestRejectEvenWithValidCsrf() throws Exception {
        mvc.perform(get("/api/settings/models").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/settings/models").with(anonymous()).with(csrf().asHeader()).contentType("application/json").content(body(Type.CHAT,"one"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/settings/models/test").with(anonymous()).with(csrf().asHeader()).contentType("application/json").content("{\"type\":\"CHAT\"}")).andExpect(status().isUnauthorized());
        assertThat(count.get()).isZero();
    }
    @Test void missingAndWrongCsrfRejectSaveAndTest() throws Exception {
        for(String token:List.of("","invalid")) {
            mvc.perform(put("/api/settings/models").header("X-CSRF-TOKEN",token).contentType("application/json").content(body(Type.CHAT,"one"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/settings/models/test").header("X-CSRF-TOKEN",token).contentType("application/json").content("{\"type\":\"CHAT\"}")).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_configs",Integer.class)).isZero();
    }
    @Test @WithMockUser(username="another",roles="ADMIN") void authenticatedDifferentOwnerCannotReadOrUpdateConfig() throws Exception {
        models.save(owner,input(Type.CHAT,"one"));jdbc.execute("ALTER TABLE users DROP CONSTRAINT users_single_administrator");
        try {
            long other=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('another',?) RETURNING id",Long.class,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("B1-Synthetic-Test-Password"));
            mvc.perform(get("/api/settings/models")).andExpect(status().isOk()).andExpect(jsonPath("$[0].configured").value(false));
            mvc.perform(put("/api/settings/models").with(csrf().asHeader()).contentType("application/json").content(body(Type.CHAT,"two"))).andExpect(status().isOk());
            assertThat(keys.decrypt(repository.find(owner,Type.CHAT).orElseThrow().cipher(),owner,Type.CHAT)).isEqualTo("one");assertThat(repository.find(other,Type.CHAT)).isPresent();
        } finally {jdbc.update("DELETE FROM model_configs WHERE owner_id<>?",owner);jdbc.update("DELETE FROM users WHERE id<>?",owner);jdbc.execute("ALTER TABLE users ADD CONSTRAINT users_single_administrator UNIQUE (singleton_key)");}
    }
    @Test void optimisticTestWriteRejectsOldRevisionAfterConfigEdit() {
        models.save(owner,input(Type.CHAT,"one"));Stored before=repository.find(owner,Type.CHAT).orElseThrow();models.save(owner,input(Type.CHAT,"two"));
        assertThat(repository.tested(before,null,null)).isFalse();assertThat(models.list(owner).get(0).testStatus()).isEqualTo("UNTESTED");
    }
    @ParameterizedTest @ValueSource(strings={"{\"type\":\"B2\"}","{\"type\":\"CHAT\",\"owner_id\":99}","{\"type\":1}","null"})
    void invalidTestInputRejected(String value) throws Exception {mvc.perform(post("/api/settings/models/test").with(csrf().asHeader()).contentType("application/json").content(value)).andExpect(status().isBadRequest());assertThat(count.get()).isZero();}
    @Test void malformedConfigCoercionAndSecretHeaderInjectionRejected() throws Exception {
        String valid=body(Type.CHAT,"one");
        for(String invalid:List.of(valid.replace("\"CHAT\"","\"B2\""),valid.replace("OPENAI_COMPATIBLE","UNKNOWN"),valid.replace("model-one","<script>"),valid.replace("\"one\"","\"bad\\r\\nCookie: x\""),valid.replace("false","\"false\""),valid.replace("\"type\":","\"owner_id\":1,\"type\":"))) mvc.perform(put("/api/settings/models").with(csrf().asHeader()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_configs",Integer.class)).isZero();
    }
}
