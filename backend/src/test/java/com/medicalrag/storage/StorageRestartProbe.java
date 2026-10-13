package com.medicalrag.storage;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import com.medicalrag.MedicalRagApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Test JVM only. Starts real Spring Boot and reads a persisted receipt; no production API. */
public class StorageRestartProbe {
    public static void main(String[] arguments) throws Exception {
        try (var application = new SpringApplicationBuilder(MedicalRagApplication.class).run("--server.port=0",
                "--app.storage.root=" + System.getenv("APP_STORAGE_ROOT"), "--app.storage.max-pdf-bytes=52428800", "--app.storage.max-image-bytes=20971520")) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "storagedoctor", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            var receipt = new StoredOriginal(arguments[1], Long.parseLong(arguments[2]), arguments[3], OriginalType.valueOf(arguments[4]), "合成重启测试.pdf");
            var output = new ByteArrayOutputStream();
            application.getBean(KnowledgeBaseFileStorage.class).copyTo(arguments[0], receipt, output);
            if (output.size() != receipt.size() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray())).equals(receipt.sha256())) {
                throw new IllegalStateException("Restarted application did not read the exact original bytes");
            }
            System.out.println("A5_RESTART_READ_OK");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
