package com.medicalrag.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Creates and owns a fresh test-only directory; never accepts an existing user's path. */
public final class TestStorageRoot implements AutoCloseable {
    private final Path root;
    private TestStorageRoot(Path root) { this.root = root; }
    public static TestStorageRoot create() {
        try { return new TestStorageRoot(Files.createTempDirectory(Path.of("target"), "a5-storage-test-").toAbsolutePath()); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    public String value() { return root.toString(); }
    public Path path() { return root; }
    public void configure(DynamicPropertyRegistry registry) {
        registry.add("app.image.max-width", () -> 8192);
        registry.add("app.image.max-height", () -> 8192);
        registry.add("app.image.max-pixels", () -> 16000000L);
        registry.add("app.storage.root", this::value);
        registry.add("app.storage.max-pdf-bytes", () -> 52428800L);
        registry.add("app.storage.max-image-bytes", () -> 20971520L);
    }
    public void configureProcess(ProcessBuilder builder) {
        builder.environment().put("APP_STORAGE_ROOT", value());
        builder.environment().put("APP_STORAGE_MAX_PDF_BYTES", "52428800");
        builder.environment().put("APP_STORAGE_MAX_IMAGE_BYTES", "20971520");
    }
    public void configureBootArguments(ProcessBuilder builder) {
        configureProcess(builder);
        builder.command().add("--app.image.max-width=8192");
        builder.command().add("--app.image.max-height=8192");
        builder.command().add("--app.image.max-pixels=16000000");
        // Command-line properties also override any inherited JSON/profile root.
        builder.command().add("--app.storage.root=" + value());
        builder.command().add("--app.storage.max-pdf-bytes=52428800");
        builder.command().add("--app.storage.max-image-bytes=20971520");
    }
    @Override public void close() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
