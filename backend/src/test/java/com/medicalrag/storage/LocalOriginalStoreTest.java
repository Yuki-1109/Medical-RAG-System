package com.medicalrag.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalOriginalStoreTest {
    @TempDir Path temporary;
    final StorageScope scope = new StorageScope(17);
    Path root() { return temporary.resolve("医学 原件 storage"); }
    LocalOriginalStore store(StorageIo io) { return new LocalOriginalStore(new StorageProperties(root().toString(), 52428800, 20971520), root(), io); }
    LocalOriginalStore store() { return store(new StorageIo()); }
    byte[] bytes(OriginalType type) throws Exception {
        return switch (type) { case PDF -> OriginalTestFiles.pdf(); case PNG -> OriginalTestFiles.image("png"); case JPEG -> OriginalTestFiles.image("jpg"); };
    }
    StoredOriginal save(LocalOriginalStore store, OriginalType type, byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes)) { return store.save(scope, "合成测试." + type.extension(), type, input); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    byte[] read(LocalOriginalStore store, StoredOriginal receipt) {
        var output = new ByteArrayOutputStream(); store.copyTo(scope, receipt, output); return output.toByteArray();
    }
    Path physical(StoredOriginal receipt) { return root().resolve(".storage-objects").resolve(receipt.storageKey()); }
    void noPendingFiles() throws Exception { try (var directories = Files.list(root().resolve(".storage-staging"))) { assertThat(directories.toList()).isEmpty(); } }
    void code(StorageException.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(StorageException.class, error -> assertThat(error.code()).isEqualTo(code));
    }

    @ParameterizedTest @EnumSource(OriginalType.class)
    void actualBytesHashAndReceiptSurviveRecreatingTheStore(OriginalType type) throws Exception {
        LocalOriginalStore store = store(); byte[] original = bytes(type);
        StoredOriginal receipt = save(store, type, original);
        assertThat(receipt.size()).isEqualTo(original.length);
        assertThat(receipt.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        assertThat(receipt.type()).isEqualTo(type);
        assertThat(receipt.storageKey()).startsWith(type.namespace() + "/17/").doesNotContain("合成测试");
        assertThat(read(store, receipt)).isEqualTo(original);
        assertThat(read(store(), receipt)).isEqualTo(original);
        noPendingFiles();
        // Windows would expose leaked store-owned file handles here.
        store.delete(scope, receipt);
        assertThat(Files.exists(physical(receipt))).isFalse();
    }
    @Test void identicalNamesHaveDifferentIdsAndDeletingOneDoesNotTouchAnother() throws Exception {
        var store = store(); byte[] pdf = bytes(OriginalType.PDF);
        var first = save(store, OriginalType.PDF, pdf); var second = save(store, OriginalType.PDF, pdf);
        assertThat(first.originalName()).isEqualTo(second.originalName());
        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
        store.delete(scope, first);
        assertThat(read(store, second)).isEqualTo(pdf);
        code(StorageException.Code.NOT_FOUND, () -> store.copyTo(scope, first, OutputStream.nullOutputStream()));
        code(StorageException.Code.NOT_FOUND, () -> store.delete(scope, first));
    }
    @ParameterizedTest @EnumSource(OriginalType.class)
    void limitsAllowBelowAndExactlyTheLimitButRejectOneByteOver(OriginalType type) throws Exception {
        byte[] bytes = bytes(type);
        for (int limit : new int[] {bytes.length + 1, bytes.length}) {
            var store = new LocalOriginalStore(new StorageProperties(root().toString(), limit, limit), root(), new StorageIo());
            assertThat(read(store, save(store, type, bytes))).isEqualTo(bytes);
        }
        var constrained = new LocalOriginalStore(new StorageProperties(root().toString(), bytes.length - 1, bytes.length - 1), root(), new StorageIo());
        code(StorageException.Code.TOO_LARGE, () -> save(constrained, type, bytes)); noPendingFiles();
    }
    @Test void emptyFakeSignaturesMismatchedExtensionsAndTruncatedFilesAreRejected() throws Exception {
        var store = store();
        code(StorageException.Code.EMPTY_FILE, () -> save(store, OriginalType.PDF, new byte[0]));
        code(StorageException.Code.UNSUPPORTED_FORMAT, () -> save(store, OriginalType.PDF, "not a PDF".getBytes()));
        code(StorageException.Code.FORMAT_MISMATCH, () -> save(store, OriginalType.PDF, bytes(OriginalType.PNG)));
        code(StorageException.Code.FORMAT_MISMATCH, () -> store.save(scope, "伪装.jpg", OriginalType.PDF, new ByteArrayInputStream(bytes(OriginalType.PDF))));
        for (OriginalType type : OriginalType.values()) {
            byte[] valid = bytes(type);
            code(StorageException.Code.DAMAGED_FILE, () -> save(store, type, Arrays.copyOf(valid, valid.length - 5)));
        }
        code(StorageException.Code.DAMAGED_FILE, () -> save(store, OriginalType.PDF, "%PDF-1.7\nonly a signature, not a document\n%%EOF\n".getBytes()));
        noPendingFiles();
    }
    @Test void pngCrcAndPdfCrossReferenceOffsetAreCheckedWithoutDecoding() throws Exception {
        var store = store(); byte[] png = bytes(OriginalType.PNG); png[29] ^= 1;
        code(StorageException.Code.DAMAGED_FILE, () -> save(store, OriginalType.PNG, png));
        String pdf = new String(bytes(OriginalType.PDF), java.nio.charset.StandardCharsets.US_ASCII).replaceFirst("startxref\\n[0-9]+", "startxref\n1");
        code(StorageException.Code.DAMAGED_FILE, () -> save(store, OriginalType.PDF, pdf.getBytes())); noPendingFiles();
    }
    @ParameterizedTest @ValueSource(strings = {"../a.pdf", "..\\a.pdf", "/tmp/a.pdf", "C:\\private\\a.pdf", "C:relative.pdf", "\\\\server\\share\\a.pdf", "a\u0000.pdf"})
    void originalFilenamePathsCannotDetermineDiskLocation(String name) throws Exception {
        var store = store();
        code(StorageException.Code.INVALID_NAME, () -> store.save(scope, name, OriginalType.PDF, new ByteArrayInputStream(bytes(OriginalType.PDF))));
        noPendingFiles();
    }
    @ParameterizedTest @ValueSource(strings = {"../", "..\\", "/etc/passwd", "C:\\Windows\\win.ini", "C:relative", "\\\\server\\share\\file", "documents/17/../../other", "documents/17/%2e%2e/file", "documents\\17\\file", "documents/17/00000000-0000-0000-0000-000000000000/original.pdf/extra"})
    void arbitraryDiskPathsCannotBecomeReadOrDeleteReceipts(String key) {
        code(StorageException.Code.INVALID_REFERENCE, () -> new StoredOriginal(key, 1, "0".repeat(64), OriginalType.PDF, "metadata.pdf"));
    }
    @Test void aRealReceiptCannotBeUsedForAnotherLibrary() throws Exception {
        var store = store(); var receipt = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        code(StorageException.Code.INVALID_REFERENCE, () -> store.copyTo(new StorageScope(18), receipt, OutputStream.nullOutputStream()));
        code(StorageException.Code.INVALID_REFERENCE, () -> store.delete(new StorageScope(18), receipt));
        assertThat(read(store, receipt)).isEqualTo(bytes(OriginalType.PDF));
    }
    @Test void tamperedBytesAreNeitherReturnedNorDeleted() throws Exception {
        var store = store(); var receipt = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        Files.writeString(physical(receipt), "tampered bytes");
        var destination = new ByteArrayOutputStream();
        code(StorageException.Code.INTEGRITY_MISMATCH, () -> store.copyTo(scope, receipt, destination));
        assertThat(destination.size()).isZero();
        code(StorageException.Code.INTEGRITY_MISMATCH, () -> store.delete(scope, receipt));
        assertThat(Files.exists(physical(receipt))).isTrue();
    }
    @Test void interruptedSourceAndThreadCancellationCleanActualStageFiles() throws Exception {
        var store = store(); var existing = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        InputStream broken = new InputStream() {
            int count;
            public int read() throws IOException { if (count++ > 10) throw new IOException("injected source interruption"); return 'x'; }
        };
        try (broken) { code(StorageException.Code.IO_FAILURE, () -> store.save(scope, "abort.pdf", OriginalType.PDF, broken)); }
        noPendingFiles();
        try {
            Thread.currentThread().interrupt();
            code(StorageException.Code.IO_FAILURE, () -> save(store, OriginalType.PDF, bytes(OriginalType.PDF)));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        noPendingFiles(); assertThat(read(store, existing)).isEqualTo(bytes(OriginalType.PDF));
    }
    @Test void unsupportedAtomicCommitAndPermissionsFailuresCompensateWithoutPublishing() throws Exception {
        var good = store(); var existing = save(good, OriginalType.PDF, bytes(OriginalType.PDF));
        var unsupported = store(new StorageIo() { @Override void publish(Path stage, Path target) throws IOException { throw new AtomicMoveNotSupportedException(stage.toString(), target.toString(), "injected unsupported atomic move"); } });
        code(StorageException.Code.ATOMIC_COMMIT_UNSUPPORTED, () -> save(unsupported, OriginalType.PDF, bytes(OriginalType.PDF))); noPendingFiles();
        var denied = store(new StorageIo() { @Override FileChannel createFile(Path file) throws IOException { throw new AccessDeniedException(file.toString()); } });
        code(StorageException.Code.ACCESS_DENIED, () -> save(denied, OriginalType.PDF, bytes(OriginalType.PDF))); noPendingFiles();
        var moveDenied = store(new StorageIo() { @Override void publish(Path stage, Path target) throws IOException { throw new AccessDeniedException(target.toString()); } });
        code(StorageException.Code.ACCESS_DENIED, () -> save(moveDenied, OriginalType.PDF, bytes(OriginalType.PDF))); noPendingFiles();
        assertThat(read(good, existing)).isEqualTo(bytes(OriginalType.PDF));
    }
    @Test void collidingUuidCannotOverwriteAnExistingOriginal() throws Exception {
        UUID fixed = UUID.randomUUID();
        var store = new LocalOriginalStore(new StorageProperties(root().toString(), 52428800, 20971520), root(), new StorageIo(), () -> fixed);
        var existing = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        code(StorageException.Code.ALREADY_EXISTS, () -> save(store, OriginalType.PDF, bytes(OriginalType.PDF)));
        assertThat(read(store, existing)).isEqualTo(bytes(OriginalType.PDF)); noPendingFiles();
    }
    @Test void failedCleanupIsReportedAndNeverDeletesAnUnrecognizedFile() throws Exception {
        var store = store(new StorageIo() {
            @Override void publish(Path stage, Path target) throws IOException {
                Files.writeString(stage.resolve("unknown.txt"), "preserve");
                throw new IOException("injected commit failure");
            }
        });
        assertThatThrownBy(() -> save(store, OriginalType.PDF, bytes(OriginalType.PDF))).isInstanceOfSatisfying(StorageException.class, exception -> {
            assertThat(exception.code()).isEqualTo(StorageException.Code.IO_FAILURE);
            assertThat(exception.getSuppressed()).hasSize(1);
            assertThat(((StorageException)exception.getSuppressed()[0]).code()).isEqualTo(StorageException.Code.CLEANUP_FAILURE);
        });
        try (var files = Files.walk(root().resolve(".storage-staging"))) {
            Path unknown = files.filter(path -> path.getFileName().toString().equals("unknown.txt")).findFirst().orElseThrow();
            assertThat(Files.readString(unknown)).isEqualTo("preserve");
        }
    }
    @Test void deleteErrorsRemainErrorsAndTheOriginalRemainsReadable() throws Exception {
        var good = store(); var receipt = save(good, OriginalType.PDF, bytes(OriginalType.PDF));
        var denied = store(new StorageIo() { @Override void deleteFile(Path file) throws IOException { throw new AccessDeniedException(file.toString()); } });
        code(StorageException.Code.ACCESS_DENIED, () -> denied.delete(scope, receipt));
        var busy = store(new StorageIo() { @Override void deleteFile(Path file) throws IOException { throw new FileSystemException(file.toString(), null, "being used by another process"); } });
        code(StorageException.Code.FILE_IN_USE, () -> busy.delete(scope, receipt));
        var failed = store(new StorageIo() { @Override void deleteFile(Path file) throws IOException { throw new IOException("injected delete I/O failure"); } });
        code(StorageException.Code.IO_FAILURE, () -> failed.delete(scope, receipt));
        assertThat(read(good, receipt)).isEqualTo(bytes(OriginalType.PDF));
    }
    @Test void failedDestinationDoesNotLeakTheStoresFileHandle() throws Exception {
        var store = store(); var receipt = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        var output = new OutputStream() { public void write(int value) throws IOException { throw new IOException("injected output failure"); } };
        code(StorageException.Code.IO_FAILURE, () -> store.copyTo(scope, receipt, output));
        store.delete(scope, receipt); assertThat(Files.exists(physical(receipt))).isFalse();
    }
    void symlink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | SecurityException exception) { assumeTrue(false, "A5 symlink check unavailable: " + exception.getClass().getSimpleName()); }
        catch (FileSystemException exception) {
            String reason = String.valueOf(exception.getReason()).toLowerCase(Locale.ROOT);
            if (System.getProperty("os.name").startsWith("Windows") && (exception instanceof AccessDeniedException || reason.contains("privilege") || reason.contains("特权") || reason.contains("权限"))) assumeTrue(false, "A5 Windows symlink privilege unavailable: " + reason);
            else throw exception;
        }
    }
    @Test void leafSymlinkCannotReadOrDeleteAnOutsideFile() throws Exception {
        var store = store(); var receipt = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "do not read or delete");
        Files.delete(physical(receipt)); symlink(physical(receipt), outside);
        code(StorageException.Code.INVALID_REFERENCE, () -> store.copyTo(scope, receipt, OutputStream.nullOutputStream()));
        code(StorageException.Code.INVALID_REFERENCE, () -> store.delete(scope, receipt));
        assertThat(Files.readString(outside)).isEqualTo("do not read or delete");
    }
    @Test void parentOrRootSymlinksCannotRedirectAnyOperation() throws Exception {
        var store = store(); var receipt = save(store, OriginalType.PDF, bytes(OriginalType.PDF));
        Path parent = physical(receipt).getParent(); Path moved = parent.resolveSibling("kept-original-directory");
        Files.move(parent, moved); symlink(parent, temporary);
        code(StorageException.Code.INVALID_REFERENCE, () -> store.copyTo(scope, receipt, OutputStream.nullOutputStream()));
        code(StorageException.Code.INVALID_REFERENCE, () -> store.delete(scope, receipt));
        Files.delete(parent); Files.move(moved, parent);
        Path movedRoot = root().resolveSibling("original-root"); Files.move(root(), movedRoot); symlink(root(), movedRoot);
        code(StorageException.Code.INVALID_REFERENCE, () -> save(store, OriginalType.PDF, bytes(OriginalType.PDF)));
        code(StorageException.Code.INVALID_REFERENCE, () -> store.copyTo(scope, receipt, OutputStream.nullOutputStream()));
        code(StorageException.Code.INVALID_REFERENCE, () -> store());
    }
    @Test void posixWritePermissionFailureIsRealWhenThePlatformSupportsIt() throws Exception {
        var store = store(); Path stage = root().resolve(".storage-staging");
        assumeTrue(stage.getFileSystem().supportedFileAttributeViews().contains("posix"), "A5 POSIX permission test unavailable; access-denied compensation is tested separately");
        var previous = Files.getPosixFilePermissions(stage);
        try {
            Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString("r-x------"));
            assumeTrue(!Files.isWritable(stage), "Current test account bypasses POSIX permissions; injected access-denied tests still execute");
            code(StorageException.Code.ACCESS_DENIED, () -> save(store, OriginalType.PDF, bytes(OriginalType.PDF)));
        } finally { Files.setPosixFilePermissions(stage, previous); }
        noPendingFiles();
    }
}
