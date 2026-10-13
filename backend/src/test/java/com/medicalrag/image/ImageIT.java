package com.medicalrag.image;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalrag.knowledgebase.*;
import com.medicalrag.storage.*;
import com.medicalrag.testutil.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@WithMockUser(username="pdfdoctor",roles="ADMIN")
class ImageIT {
    private static final TestStorageRoot STORAGE=TestStorageRoot.create();
    static final String PASSWORD="A7-Synthetic-Integration-Password";
    @AfterAll static void closeStorage() throws Exception { STORAGE.close(); }
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        STORAGE.configure(properties); properties.add("app.storage.max-image-bytes",()->4096L);
        properties.add("spring.datasource.url",DATABASE::getJdbcUrl); properties.add("spring.datasource.username",DATABASE::getUsername); properties.add("spring.datasource.password",DATABASE::getPassword);
    }
    @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder passwords; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired ImageService images; @Autowired ImageRepository repository; @Autowired KnowledgeBaseService bases;
    @MockitoSpyBean KnowledgeBaseFileStorage files;
    @LocalServerPort int port;
    long owner,k1,k2;
    @BeforeEach void fixtures() {
        reset(files);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_activate ON medical_images"); jdbc.execute("DROP TRIGGER IF EXISTS reject_remove ON medical_images");
        jdbc.update("DELETE FROM documents"); jdbc.update("DELETE FROM medical_images"); jdbc.update("DELETE FROM knowledge_bases"); jdbc.update("DELETE FROM users");
        owner=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('pdfdoctor',?) RETURNING id",Long.class,passwords.encode(PASSWORD));
        k1=Long.parseLong(bases.create(owner,"A7 K1","").id()); k2=Long.parseLong(bases.create(owner,"A7 K2","").id());
    }
    MockMultipartHttpServletRequestBuilder upload(long base,String name,byte[] bytes) { return multipart("/api/knowledge-bases/"+base+"/images").file(new MockMultipartFile("file",name,"application/octet-stream",bytes)); }
    ImageInfo upload(long base) throws Exception {
        restoreAuthentication();
        try (var input=new ByteArrayInputStream(SyntheticImages.png())) { return images.upload(base,owner,"合成 English research \"v1\".png",input); }
    }
    void restoreAuthentication() { SecurityContextHolder.setContext(org.springframework.security.test.context.TestSecurityContextHolder.getContext()); }
    Path physical(String id) { return STORAGE.path().resolve(".storage-objects").resolve(jdbc.queryForObject("SELECT storage_key FROM medical_images WHERE id=?",String.class,Long.parseLong(id))); }
    String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }

    @Test void newSchemaUploadsSameNameIndependentlyAndReportsRealMetadataCountsAndPendingStatus() throws Exception {
        byte[] source=SyntheticImages.png();
        var first=json.readTree(mvc.perform(upload(k1,"合成 English research \"v1\".png",source).with(csrf().asHeader())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id=first.get("id").asText(); var second=upload(k1);
        assertThat(first.get("id").isTextual()).isTrue(); assertThat(first.get("knowledgeBaseId").asText()).isEqualTo(Long.toString(k1));
        assertThat(first.get("height").asInt()).isEqualTo(24); assertThat(first.get("fileSize").asLong()).isEqualTo(source.length); assertThat(first.get("sha256").asText()).isEqualTo(hash(source));
        assertThat(first.get("imageType").asText()).isEqualTo("UNCLASSIFIED"); assertThat(first.has("storageKey")).isFalse(); assertThat(id).isNotEqualTo(second.id());
        assertThat(physical(id)).isNotEqualTo(physical(second.id())); assertThat(Files.readAllBytes(physical(id))).isEqualTo(source);
        mvc.perform(get("/api/knowledge-bases/"+k1+"/images")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/knowledge-bases/"+k2+"/images")).andExpect(jsonPath("$.length()").value(0));
        assertThat(bases.detail(k1,owner).imageCount()).isEqualTo(2); assertThat(bases.detail(k2,owner).imageCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='5' AND success",Integer.class)).isEqualTo(1);
    }
    @Test void downloadAndPreviewAreProtectedExactOriginalsWithSafeUnicodeHeadersAndNoRangeClaim() throws Exception {
        var document=upload(k1); byte[] source=SyntheticImages.png();
        for (String endpoint : new String[] {"content","download"}) {
            var response=mvc.perform(get("/api/images/"+document.id()+"/"+endpoint).header("Range","bytes=0-15"))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                    .andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("Accept-Ranges","none"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse();
            assertThat(response.getContentAsByteArray()).isEqualTo(source); assertThat(hash(response.getContentAsByteArray())).isEqualTo(document.sha256());
            assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''").doesNotContain("\r","\n");
        }
        assertThat(Files.readAllBytes(physical(document.id()))).isEqualTo(source);
    }
    @Test void emptyFakeDamagedAndOverLimitImagesLeaveNoValidOrIncompleteRecords() throws Exception {
        for (byte[] data : new byte[][] {new byte[0],"not a PDF".getBytes(),Arrays.copyOf(SyntheticImages.png(),40)}) {
            mvc.perform(upload(k1,"bad.png",data).with(csrf().asHeader())).andExpect(status().isBadRequest());
        }
        mvc.perform(upload(k1,"mismatch.png",SyntheticImages.jpeg()).with(csrf().asHeader())).andExpect(status().isBadRequest());
        mvc.perform(upload(k1,"large.png",new byte[4097]).with(csrf().asHeader())).andExpect(status().isPayloadTooLarge());
        assertThat(images.list(k1,owner)).isEmpty(); assertThat(bases.detail(k1,owner).pendingCleanupCount()).isZero();
    }
    @Test void changedCountsAfterConfirmationDoNotDeleteAnyKnowledgeBaseOrImage() throws Exception {
        var document=upload(k1); Path file=physical(document.id());
        mvc.perform(delete("/api/knowledge-bases/"+k1).param("expectedImageCount","0").param("expectedPendingCleanupCount","0").with(csrf().asHeader()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_CHANGED"));
        assertThat(bases.detail(k1,owner).deletionPending()).isFalse(); assertThat(Files.exists(file)).isTrue(); assertThat(images.detail(Long.parseLong(document.id()),owner).lifecycleStatus()).isEqualTo("ACTIVE");
    }
    @Test void multipartDoesNotAcceptClientOwnershipPathsHashesOrExtraFiles() throws Exception {
        for (String field : new String[] {"owner_id","storageKey","sha256","id","path"}) {
            mvc.perform(upload(k1,"valid.png",SyntheticImages.png()).param(field,"../private").with(csrf().asHeader())).andExpect(status().isBadRequest());
        }
        mvc.perform(upload(k1,"valid.png",SyntheticImages.png()).file(new MockMultipartFile("file","second.png","image/png",SyntheticImages.png())).with(csrf().asHeader())).andExpect(status().isBadRequest());
        assertThat(images.list(k1,owner)).isEmpty();
    }
    @Test void unauthenticatedReadsAndWritesCannotReachPrivateResourcesEvenWithValidCsrf() throws Exception {
        var document=upload(k1);
        for (String path : new String[] {"/api/knowledge-bases/"+k1+"/images","/api/images/"+document.id(),"/api/images/"+document.id()+"/content","/api/images/"+document.id()+"/download"}) mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(upload(k1,"x.png",SyntheticImages.png()).with(anonymous()).with(csrf().asHeader())).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/images/"+document.id()).with(anonymous()).with(csrf().asHeader()).contentType("application/json").content("{\"imageType\":\"NORMAL\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/images/"+document.id()).with(anonymous()).with(csrf().asHeader())).andExpect(status().isUnauthorized());
        assertThat(Files.exists(physical(document.id()))).isTrue();
    }
    @Test void missingOrWrongCsrfRejectsUploadsAndDeletionsBeforeChangingData() throws Exception {
        var document=upload(k1);
        mvc.perform(upload(k1,"x.png",SyntheticImages.png())).andExpect(status().isForbidden());
        mvc.perform(upload(k1,"x.png",SyntheticImages.png()).with(csrf().asHeader().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/images/"+document.id())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/images/"+document.id()).with(csrf().asHeader().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(patch("/api/images/"+document.id()).contentType("application/json").content("{\"imageType\":\"NORMAL\"}")).andExpect(status().isForbidden());
        assertThat(images.list(k1,owner)).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"0","01","abc","-1","9223372036854775808"})
    void invalidImageIdsAreReadableErrorsWithoutDatabaseMutation(String id) throws Exception {
        for (String suffix : new String[] {"","/content","/download"}) mvc.perform(get("/api/images/"+id+suffix)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/images/"+id).with(csrf().asHeader())).andExpect(status().isBadRequest());
    }
    @Test void missingImagesAndArbitraryPathParametersDoNotExposeFilesystemInformation() throws Exception {
        var document=upload(k1);
        mvc.perform(get("/api/images/9223372036854775807")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        mvc.perform(get("/api/images/"+document.id()+"/content").param("path","C:/Windows/win.ini")).andExpect(status().isBadRequest());
        var response=mvc.perform(get("/api/images/"+document.id())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(STORAGE.value(),"storageKey","password","owner_id");
    }
    @Test @Transactional void foreignOwnerCannotReadPreviewDownloadDeleteOrUploadAndNoPrivateNameLeaks() throws Exception {
        var document=upload(k1); Path file=physical(document.id());
        jdbc.execute("ALTER TABLE users DROP CONSTRAINT users_single_administrator");
        long other=jdbc.queryForObject("INSERT INTO users (username,password_hash) VALUES ('foreignpdf',?) RETURNING id",Long.class,passwords.encode("Synthetic-Foreign-Only-Password"));
        jdbc.update("UPDATE knowledge_bases SET owner_id=? WHERE id=?",other,k1);
        for (String suffix : new String[] {"","/content","/download"}) {
            var response=mvc.perform(get("/api/images/"+document.id()+suffix)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(document.originalFilename(),document.sha256(),STORAGE.value());
        }
        mvc.perform(delete("/api/images/"+document.id()).with(csrf().asHeader())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/images/"+document.id()).with(csrf().asHeader()).contentType("application/json").content("{\"imageType\":\"NORMAL\"}")).andExpect(status().isNotFound());
        mvc.perform(upload(k1,"new.png",SyntheticImages.png()).with(csrf().asHeader())).andExpect(status().isNotFound());
        assertThat(Files.exists(file)).isTrue(); assertThat(jdbc.queryForObject("SELECT count(*) FROM medical_images",Integer.class)).isEqualTo(1);
    }
    @Test void missingOrTamperedOriginalProducesAnErrorBeforeSendingAnyImageBytes() throws Exception {
        var document=upload(k1); Path file=physical(document.id()); byte[] original=Files.readAllBytes(file);
        Files.writeString(file,"tampered");
        mvc.perform(get("/api/images/"+document.id()+"/download")).andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.code").value("STORAGE_INTEGRITY_MISMATCH"));
        Files.write(file,original); Files.delete(file);
        mvc.perform(get("/api/images/"+document.id()+"/content")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_NOT_FOUND"));
        assertThat(images.detail(Long.parseLong(document.id()),owner).sha256()).isEqualTo(document.sha256());
    }
    @Test void deletingOneImageCleansItsOriginalUpdatesCountAndPreservesOtherLibraries() throws Exception {
        var first=upload(k1); var same=upload(k1); var other=upload(k2); Path file=physical(first.id()), sameFile=physical(same.id()), otherFile=physical(other.id());
        mvc.perform(delete("/api/images/"+first.id()).with(csrf().asHeader())).andExpect(status().isNoContent());
        assertThat(Files.exists(file)).isFalse(); assertThat(Files.exists(sameFile)).isTrue(); assertThat(Files.exists(otherFile)).isTrue();
        assertThat(bases.detail(k1,owner).imageCount()).isEqualTo(1); assertThat(bases.detail(k2,owner).imageCount()).isEqualTo(1);
        for (String suffix : new String[] {"","/content","/download"}) mvc.perform(get("/api/images/"+first.id()+suffix)).andExpect(status().isNotFound());
    }
    @Test void fileCleanupFailureKeepsCredentialsBlocksAccessAndSupportsExplicitRetry() throws Exception {
        var document=upload(k1); Path file=physical(document.id());
        doThrow(new StorageException(StorageException.Code.FILE_IN_USE,"原件正在被其他程序占用，请关闭后重试")).when(files).delete(anyString(),any());
        mvc.perform(delete("/api/images/"+document.id()).with(csrf().asHeader())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_FILE_IN_USE"));
        assertThat(Files.exists(file)).isTrue(); assertThat(bases.detail(k1,owner).imageCount()).isZero(); assertThat(bases.detail(k1,owner).pendingCleanupCount()).isEqualTo(1);
        assertThat(images.list(k1,owner).getFirst().lifecycleStatus()).isEqualTo("DELETE_FAILED");
        mvc.perform(get("/api/images/"+document.id()+"/content")).andExpect(status().isNotFound());
        reset(files); restoreAuthentication(); images.delete(Long.parseLong(document.id()),owner);
        assertThat(Files.exists(file)).isFalse(); assertThat(images.list(k1,owner)).isEmpty();
    }
    void activationFailure() {
        jdbc.execute("CREATE OR REPLACE FUNCTION a7_reject_activate() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic activation failure''; END'");
        jdbc.execute("CREATE TRIGGER reject_activate BEFORE UPDATE ON medical_images FOR EACH ROW WHEN (NEW.lifecycle_status = 'ACTIVE') EXECUTE FUNCTION a7_reject_activate()");
    }
    @Test void actualDatabaseActivationFailureCompensatesThePublishedFile() throws Exception {
        activationFailure();
        mvc.perform(upload(k1,"failed.png",SyntheticImages.png()).with(csrf().asHeader())).andExpect(status().isServiceUnavailable());
        assertThat(images.list(k1,owner)).isEmpty(); assertThat(bases.detail(k1,owner).imageCount()).isZero();
        try (var walk=Files.walk(STORAGE.path().resolve(".storage-objects/images/"+k1))) { assertThat(walk.filter(Files::isRegularFile).count()).isZero(); }
    }
    @Test void failedUploadCompensationPreservesServerIntentAndCanBeRetriedAfterRestart() throws Exception {
        activationFailure(); doThrow(new StorageException(StorageException.Code.FILE_IN_USE,"合成占用")).when(files).discardReservation(anyString(),any());
        mvc.perform(upload(k1,"failed.png",SyntheticImages.png()).with(csrf().asHeader())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UPLOAD_CLEANUP_PENDING"));
        var pending=images.list(k1,owner).getFirst(); Path file=physical(pending.id());
        assertThat(pending.lifecycleStatus()).isEqualTo("DELETE_FAILED"); assertThat(pending.sha256()).isNull(); assertThat(Files.exists(file)).isTrue();
        assertThat(bases.detail(k1,owner).imageCount()).isZero(); reset(files); restoreAuthentication(); images.delete(Long.parseLong(pending.id()),owner);
        assertThat(Files.exists(file)).isFalse(); assertThat(images.list(k1,owner)).isEmpty();
    }
    @Test void databaseRemovalFailureAfterFileDeletionIsRetryableWithoutFalseActiveResource() throws Exception {
        var document=upload(k1); Path file=physical(document.id());
        jdbc.execute("CREATE OR REPLACE FUNCTION a7_reject_remove() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic removal failure''; END'");
        jdbc.execute("CREATE TRIGGER reject_remove BEFORE DELETE ON medical_images FOR EACH ROW EXECUTE FUNCTION a7_reject_remove()");
        mvc.perform(delete("/api/images/"+document.id()).with(csrf().asHeader())).andExpect(status().isServiceUnavailable());
        assertThat(Files.exists(file)).isFalse(); assertThat(images.list(k1,owner).getFirst().lifecycleStatus()).isEqualTo("DELETE_FAILED");
        mvc.perform(get("/api/images/"+document.id()+"/download")).andExpect(status().isNotFound());
        jdbc.execute("DROP TRIGGER reject_remove ON medical_images"); restoreAuthentication(); images.delete(Long.parseLong(document.id()),owner); assertThat(images.list(k1,owner)).isEmpty();
    }
    @Test void incompletePublishedAndStagedServerIntentsAreCleanableWithoutLosingUnknownFiles() throws Exception {
        var reservation=files.reserve(Long.toString(k1),OriginalType.PNG);
        var record=repository.reserve(k1,"interrupted.png",reservation);
        files.saveReserved(Long.toString(k1),reservation,"interrupted.png",new ByteArrayInputStream(SyntheticImages.png()));
        Path published=physical(record.info().id()); images.delete(record.id(),owner); assertThat(Files.exists(published)).isFalse();
        var next=files.reserve(Long.toString(k1),OriginalType.PNG); var staged=repository.reserve(k1,"partial.png",next);
        Path stage=STORAGE.path().resolve(".storage-staging").resolve(next.storageKey().split("/")[2]); Files.createDirectory(stage);
        Files.writeString(stage.resolve("original.png"),"partial synthetic write"); Files.writeString(stage.resolve("unknown.txt"),"preserve unknown content");
        assertThatThrownBy(() -> images.delete(staged.id(),owner)).isInstanceOf(StorageException.class);
        assertThat(Files.readString(stage.resolve("unknown.txt"))).isEqualTo("preserve unknown content"); assertThat(images.list(k1,owner)).hasSize(1);
        Files.delete(stage.resolve("unknown.txt")); images.delete(staged.id(),owner); assertThat(Files.exists(stage)).isFalse();
    }
    @Test void nonemptyLibraryDeletionFailureKeepsOwnerBlocksUploadAndRetryPreservesK2() throws Exception {
        var first=upload(k1); var other=upload(k2); Path firstFile=physical(first.id()), otherFile=physical(other.id());
        doThrow(new StorageException(StorageException.Code.ACCESS_DENIED,"原件目录权限不足，请恢复后重试")).when(files).delete(eq(Long.toString(k1)),any());
        mvc.perform(delete("/api/knowledge-bases/"+k1).with(csrf().asHeader())).andExpect(status().isServiceUnavailable());
        assertThat(bases.detail(k1,owner).deletionPending()).isTrue(); assertThat(Files.exists(firstFile)).isTrue();
        mvc.perform(get("/api/images/"+first.id()+"/download")).andExpect(status().isNotFound());
        mvc.perform(upload(k1,"new.png",SyntheticImages.png()).with(csrf().asHeader())).andExpect(status().isConflict());
        reset(files); mvc.perform(delete("/api/knowledge-bases/"+k1).with(csrf().asHeader())).andExpect(status().isNoContent());
        assertThat(Files.exists(firstFile)).isFalse(); assertThat(Files.exists(otherFile)).isTrue(); assertThat(bases.detail(k2,owner).imageCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medical_images WHERE knowledge_base_id=?",Integer.class,k1)).isZero();
    }
    <T> Callable<T> authenticated(Callable<T> operation) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return () -> { var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context); try { return operation.call(); } finally { SecurityContextHolder.clearContext(); } };
    }
    @Test void concurrentUploadHoldingTheLibraryLockThenDeletionLeavesNoOrphanImage() throws Exception {
        byte[] source=SyntheticImages.png(); var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1); var first=new AtomicBoolean(true);
        var input=new ByteArrayInputStream(source) {
            @Override public synchronized int read(byte[] bytes,int offset,int length) {
                if (first.getAndSet(false)) { entered.countDown(); try { if (!proceed.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
                return super.read(bytes,offset,length);
            }
        };
        try (var pool=Executors.newFixedThreadPool(2)) {
            Future<ImageInfo> uploading=pool.submit(authenticated(() -> images.upload(k1,owner,"concurrent.png",input)));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                Future<Void> deletion=pool.submit(authenticated(() -> { bases.delete(k1,owner); return null; }));
                assertThatThrownBy(() -> deletion.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                proceed.countDown(); uploading.get(15,TimeUnit.SECONDS); deletion.get(15,TimeUnit.SECONDS);
            } finally { proceed.countDown(); input.close(); }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_bases WHERE id=?",Integer.class,k1)).isZero();
        try (var walk=Files.walk(STORAGE.path().resolve(".storage-objects/images/"+k1))) { assertThat(walk.filter(Files::isRegularFile).count()).isZero(); }
    }
    @Test void deletionCommittedBeforeUploadRejectsNewFilesEvenWhileCleanupIsBusy() throws Exception {
        var first=upload(k1); var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!proceed.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); return call.callRealMethod(); }).when(files).delete(eq(Long.toString(k1)),any());
        try (var pool=Executors.newFixedThreadPool(2)) {
            Future<Void> deletion=pool.submit(authenticated(() -> { bases.delete(k1,owner); return null; }));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                Future<ImageInfo> uploading=pool.submit(authenticated(() -> images.upload(k1,owner,"too-late.png",new ByteArrayInputStream(SyntheticImages.png()))));
                proceed.countDown(); deletion.get(15,TimeUnit.SECONDS);
                assertThatThrownBy(() -> uploading.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RuntimeException.class);
            } finally { proceed.countDown(); }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medical_images WHERE knowledge_base_id=?",Integer.class,k1)).isZero();
        mvc.perform(get("/api/images/"+first.id()+"/content")).andExpect(status().isNotFound());
    }
    @Test void realHttpSessionMultipartLimitAndRestartPreserveOriginalAndMetadata() throws Exception {
        byte[] source=SyntheticImages.png(); ImageInfo document;
        try (var client=new ApiClient(port)) {
            client.login(); var uploaded=client.upload(k1,"Synthetic research.png",source); assertThat(uploaded.statusCode()).isEqualTo(201);
            document=json.readValue(uploaded.body(),ImageInfo.class);
            var tooLarge=client.upload(k1,"over-limit.png",new byte[4097]); assertThat(tooLarge.statusCode()).isEqualTo(413);
            assertThat(json.readTree(tooLarge.body()).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        }
        Path file=physical(document.id());
        try (var application=new RestartTestApplication("a6-document-restart-")) {
            for (int restart=0;restart<2;restart++) {
                int childPort; try (var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { childPort=socket.getLocalPort(); }
                var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",Path.of("target/medical-rag-backend-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString());
                builder.environment().putAll(Map.of("SERVER_PORT",Integer.toString(childPort),"SPRING_DATASOURCE_URL",DATABASE.getJdbcUrl(),"SPRING_DATASOURCE_USERNAME",DATABASE.getUsername(),"SPRING_DATASOURCE_PASSWORD",DATABASE.getPassword())); STORAGE.configureBootArguments(builder);
                Process child=application.start(builder);
                try (var client=new ApiClient(childPort)) {
                    boolean ready=false;
                    for (int attempt=0;attempt<200 && child.isAlive();attempt++) { try { if (client.get("/api/health").statusCode()==200) { ready=true; break; } } catch (IOException ignored) { } Thread.sleep(100); }
                    assertThat(ready).isTrue(); client.login();
                    assertThat(client.bytes("/api/images/"+document.id()+"/download")).isEqualTo(source);
                    var detail=json.readValue(client.get("/api/images/"+document.id()).body(),ImageInfo.class); assertThat(detail.height()).isEqualTo(24); assertThat(detail.sha256()).isEqualTo(hash(source));
                }
                application.stop(); assertThat(Files.readAllBytes(file)).isEqualTo(source);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='5' AND success",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"jpg","jpeg","png"})
    void actualFormatsDecodeToRealDimensionsAndOriginalBytes(String extension) throws Exception {
        byte[] bytes=extension.equals("png") ? SyntheticImages.png() : SyntheticImages.jpeg();
        var response=mvc.perform(upload(k1,"合成 教学."+extension,bytes).with(csrf().asHeader())).andExpect(status().isCreated()).andExpect(jsonPath("$.width").value(32)).andExpect(jsonPath("$.height").value(24)).andExpect(jsonPath("$.imageType").value("UNCLASSIFIED")).andReturn().getResponse();
        var image=json.readValue(response.getContentAsByteArray(),ImageInfo.class);
        assertThat(Files.readAllBytes(physical(image.id()))).isEqualTo(bytes);
        mvc.perform(get("/api/images/"+image.id()+"/download")).andExpect(content().bytes(bytes));
    }
    @ParameterizedTest @ValueSource(strings={"LESION","NORMAL","UNCLASSIFIED"})
    void manualInformationPersistsWithoutChangingOriginal(String type) throws Exception {
        var image=upload(k1); byte[] original=Files.readAllBytes(physical(image.id()));
        var body=new ImageMetadata(type,"Stage II","mild","teaching, contrast","匿名合成图片，只供测试");
        mvc.perform(patch("/api/images/"+image.id()).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("Stage II"));
        var persisted=images.detail(Long.parseLong(image.id()),owner); assertThat(persisted.note()).isEqualTo(body.note()); assertThat(persisted.imageType()).isEqualTo(type); assertThat(persisted.sha256()).isEqualTo(image.sha256());
        assertThat(Files.readAllBytes(physical(image.id()))).isEqualTo(original);
    }
    @ParameterizedTest @ValueSource(strings={"{\"imageType\":\"AI\"}","{\"imageType\":\"NORMAL\",\"owner_id\":1}","{\"imageType\":\"NORMAL\",\"note\":\"<script>alert(1)</script>\"}","{\"imageType\":\"NORMAL\",\"note\":123}"})
    void maliciousOrExtraMetadataIsRejected(String body) throws Exception {
        var image=upload(k1); mvc.perform(patch("/api/images/"+image.id()).with(csrf().asHeader()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertThat(images.detail(Long.parseLong(image.id()),owner).imageType()).isEqualTo("UNCLASSIFIED");
    }
    @ParameterizedTest @ValueSource(strings={"../escape.png","C:\\private.png","\\\\server\\share\\a.jpg","bad\u0000.png"})
    void clientPathsNeverReachStorage(String name) throws Exception {
        mvc.perform(upload(k1,name,SyntheticImages.png()).with(csrf().asHeader())).andExpect(status().isBadRequest()); assertThat(images.list(k1,owner)).isEmpty();
    }
    @Test void oversizedDecodedDimensionsAreRejectedWithCompensation() throws Exception {
        mvc.perform(upload(k1,"wide.png",SyntheticImages.create("png",8193,1)).with(csrf().asHeader())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_DIMENSIONS_TOO_LARGE"));
        assertThat(images.list(k1,owner)).isEmpty(); assertThat(bases.detail(k1,owner).imageCount()).isZero();
    }
    @Autowired com.medicalrag.document.DocumentService pdfs;
    com.medicalrag.document.DocumentInfo pdf(long base) throws Exception {
        restoreAuthentication(); return pdfs.upload(base,owner,"synthetic.pdf",new ByteArrayInputStream(com.medicalrag.document.SyntheticPdfs.text(1)));
    }
    Path pdfPhysical(String id) { return STORAGE.path().resolve(".storage-objects").resolve(jdbc.queryForObject("SELECT storage_key FROM documents WHERE id=?",String.class,Long.parseLong(id))); }
    @Test void mixedLibraryFailureInvalidatesAllResourcesAndRetryPreservesOtherLibrary() throws Exception {
        var pdf=pdf(k1);var picture=upload(k1);var other=upload(k2);var otherPdf=pdf(k2);
        Path pdfFile=pdfPhysical(pdf.id()),imageFile=physical(picture.id()),otherFile=physical(other.id()),otherPdfFile=pdfPhysical(otherPdf.id());
        doThrow(new StorageException(StorageException.Code.FILE_IN_USE,"合成占用")).when(files).delete(eq(Long.toString(k1)),argThat(receipt -> receipt.type()!=OriginalType.PDF));
        restoreAuthentication(); assertThatThrownBy(() -> bases.delete(k1,owner)).isInstanceOf(StorageException.class);
        assertThat(bases.detail(k1,owner).deletionPending()).isTrue(); assertThat(Files.exists(pdfFile)).isFalse(); assertThat(Files.exists(imageFile)).isTrue();
        mvc.perform(get("/api/images/"+picture.id()+"/content")).andExpect(status().isNotFound()); mvc.perform(get("/api/documents/"+pdf.id()+"/content")).andExpect(status().isNotFound());
        assertThat(bases.detail(k1,owner).pendingCleanupCount()).isEqualTo(1); reset(files);restoreAuthentication();bases.delete(k1,owner);
        assertThat(Files.exists(imageFile)).isFalse(); assertThat(Files.exists(otherFile)).isTrue(); assertThat(Files.exists(otherPdfFile)).isTrue();
        assertThat(bases.detail(k2,owner).documentCount()).isEqualTo(1); assertThat(bases.detail(k2,owner).imageCount()).isEqualTo(1);
    }
    @Test void deletingSinglePdfAndImageKeepsUnrelatedOriginalsAndIndependentCounts() throws Exception {
        var doc=pdf(k1);var image=upload(k1);var retained=upload(k1); restoreAuthentication();pdfs.delete(Long.parseLong(doc.id()),owner);
        assertThat(Files.exists(physical(image.id()))).isTrue(); assertThat(bases.detail(k1,owner).imageCount()).isEqualTo(2);
        var next=pdf(k1);restoreAuthentication();images.delete(Long.parseLong(image.id()),owner);
        assertThat(Files.exists(pdfPhysical(next.id()))).isTrue();assertThat(Files.exists(physical(retained.id()))).isTrue(); assertThat(bases.detail(k1,owner).documentCount()).isEqualTo(1);
    }
    @Test void simultaneousPdfAndImageUploadsSerializeOnLibraryAndBothRemainValid() throws Exception {
        var pdfBytes=com.medicalrag.document.SyntheticPdfs.text(1);var imageBytes=SyntheticImages.png();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(authenticated(() -> pdfs.upload(k1,owner,"parallel.pdf",new ByteArrayInputStream(pdfBytes))));
            var two=pool.submit(authenticated(() -> images.upload(k1,owner,"parallel.png",new ByteArrayInputStream(imageBytes))));
            assertThat(one.get(15,TimeUnit.SECONDS).sha256()).isEqualTo(hash(pdfBytes)); assertThat(two.get(15,TimeUnit.SECONDS).sha256()).isEqualTo(hash(imageBytes));
        }
        assertThat(bases.detail(k1,owner).documentCount()).isEqualTo(1); assertThat(bases.detail(k1,owner).imageCount()).isEqualTo(1);
    }
    @Test void downloadHoldsLibraryLockUntilStreamClosesThenDeletionCompletes() throws Exception {
        var image=upload(k1); Path originalFile=physical(image.id()); var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var reading=pool.submit(authenticated(() -> { images.content(Long.parseLong(image.id()),owner,channel -> {
                entered.countDown();try { if(!proceed.await(10,TimeUnit.SECONDS)) throw new IOException("coordination timed out"); } catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
                var output=new ByteArrayOutputStream(); var bytes=java.nio.ByteBuffer.allocate(64);for(int n;(n=channel.read(bytes))!=-1;) {output.write(bytes.array(),0,n);bytes.clear();} assertThat(output.toByteArray()).isEqualTo(SyntheticImages.png());return null;
            });return null;}));
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var deleting=pool.submit(authenticated(() -> {images.delete(Long.parseLong(image.id()),owner);return null;}));assertThatThrownBy(() -> deleting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);proceed.countDown();reading.get(15,TimeUnit.SECONDS);deleting.get(15,TimeUnit.SECONDS);} finally {proceed.countDown();}
        }
        assertThat(Files.exists(originalFile)).isFalse();
    }
    @Test void editAndDeletionAreSerializedAndCannotResurrectDeletedImage() throws Exception {
        var image=upload(k1);var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();if (!proceed.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("coordination timed out");return call.callRealMethod();}).when(files).delete(anyString(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deleting=pool.submit(authenticated(() -> {images.delete(Long.parseLong(image.id()),owner);return null;}));
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var editing=pool.submit(authenticated(() -> images.update(Long.parseLong(image.id()),owner,new ImageMetadata("NORMAL","","","",""))));proceed.countDown();deleting.get(15,TimeUnit.SECONDS);assertThatThrownBy(() -> editing.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ImageException.class);} finally {proceed.countDown();}
        }
        assertThat(images.list(k1,owner)).isEmpty();
    }
    @Test void validStructureWithCorruptCompressedPixelsFailsFullDecodeAndLeavesNoRecord() throws Exception {
        mvc.perform(upload(k1,"corrupt-pixels.png",SyntheticImages.invalidCompressedPngWithValidStructure()).with(csrf().asHeader())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DAMAGED_IMAGE"));
        assertThat(images.list(k1,owner)).isEmpty();assertThat(bases.detail(k1,owner).imageCount()).isZero();
    }
    @Test void exifJpegRemainsByteIdenticalAfterDecodeManualEditAndDownload() throws Exception {
        byte[] original=SyntheticImages.jpegWithExif();var image=images.upload(k1,owner,"anonymous exif.jpg",new ByteArrayInputStream(original));
        images.update(Long.parseLong(image.id()),owner,new ImageMetadata("NORMAL","teaching","","anonymous","EXIF retention fixture"));
        mvc.perform(get("/api/images/"+image.id()+"/download")).andExpect(status().isOk()).andExpect(content().bytes(original));assertThat(Files.readAllBytes(physical(image.id()))).isEqualTo(original);
    }
    @Test void interruptedStreamHasCommittedIntentAndIsCompensatedWithoutActiveImage() throws Exception {
        var source=new ByteArrayInputStream(SyntheticImages.png()) { @Override public synchronized int read(byte[] b,int o,int l) {throw new java.io.UncheckedIOException(new IOException("synthetic interrupted source"));} };
        assertThatThrownBy(() -> images.upload(k1,owner,"interrupted.png",source)).isInstanceOf(RuntimeException.class);
        assertThat(images.list(k1,owner)).isEmpty();assertThat(bases.detail(k1,owner).pendingCleanupCount()).isZero();
    }
    @Test void noPublicOriginalImagePathOrSpoofedListOwnershipExists() throws Exception {
        var image=upload(k1); String key=jdbc.queryForObject("SELECT storage_key FROM medical_images WHERE id=?",String.class,Long.parseLong(image.id()));
        for (String prefix : new String[]{"/data/originals/","/.storage-objects/","/originals/"}) assertThat(mvc.perform(get(prefix+key).with(anonymous())).andReturn().getResponse().getStatus()).isIn(401,404);
    }
    @Test void deletionWaitingForUploadMustRecheckCountsAfterLockAndRejectOldConfirmation() throws Exception {
        byte[] source=SyntheticImages.png();var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);var first=new AtomicBoolean(true);
        var input=new ByteArrayInputStream(source) {
            @Override public synchronized int read(byte[] b,int o,int l) {
                if(first.getAndSet(false)) {entered.countDown();try {if(!proceed.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("coordination timed out");} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                return super.read(b,o,l);
            }
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var uploading=pool.submit(authenticated(() -> images.upload(k1,owner,"confirmed-after-wait.png",input)));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var deleting=pool.submit(authenticated(() -> {bases.delete(k1,owner,0L,0L,1L);return null;}));
                assertThatThrownBy(() -> deleting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                proceed.countDown();var image=uploading.get(15,TimeUnit.SECONDS);
                assertThatThrownBy(() -> deleting.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).cause().isInstanceOf(KnowledgeBaseException.class).hasMessageContaining("数量已变化");
                assertThat(Files.readAllBytes(physical(image.id()))).isEqualTo(source);
            } finally {proceed.countDown();input.close();}
        }
        assertThat(bases.detail(k1,owner).deletionPending()).isFalse();assertThat(bases.detail(k1,owner).imageCount()).isEqualTo(1);
    }
    class ApiClient implements AutoCloseable {
        final String base; final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(1)).build();
        ApiClient(int port) { base="http://127.0.0.1:"+port; }
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        void login() throws Exception {
            String csrf=json.readTree(get("/api/auth/csrf").body()).get("token").asText();
            var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login")).header("X-CSRF-TOKEN",csrf).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=pdfdoctor&password="+PASSWORD)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).isEqualTo(200);
        }
        HttpResponse<String> upload(long baseId,String name,byte[] bytes) throws Exception {
            String token=json.readTree(get("/api/auth/csrf").body()).get("token").asText(); String boundary="A7SyntheticBoundary";
            var body=new ByteArrayOutputStream(); body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8)); body.write(bytes); body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.send(HttpRequest.newBuilder(URI.create(base+"/api/knowledge-bases/"+baseId+"/images")).header("X-CSRF-TOKEN",token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        }
        byte[] bytes(String path) throws Exception { var response=http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()); assertThat(response.statusCode()).isEqualTo(200); return response.body(); }
        public void close() { http.close(); }
    }
}
