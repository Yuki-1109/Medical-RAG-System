package com.medicalrag.testutil;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RestartTestApplicationTest {
    @TempDir Path directory;

    private ProcessBuilder child(String mode, String marker) {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                ProcessOutputFixture.class.getName(), mode, marker);
    }

    private void awaitOutput(Path log, String marker) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (Files.readString(log).contains(marker)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Test child did not produce its ready output");
    }

    @Test void drainsLargeOutputAndClosesTheLogBeforeReuseAndDeletion() throws Exception {
        Path log;
        Process second;
        try (var application = new RestartTestApplication(directory, "log with spaces-")) {
            log = application.logPath();
            Process first = application.start(child("large", "FIRST"));
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).isZero();
            application.stop();
            assertThat(Files.readString(log)).contains("isolated-output-16383", "FIRST-STDOUT", "FIRST-STDERR");
            second = application.start(child("linger", "SECOND"));
            awaitOutput(log, "SECOND-STDERR");
            application.stop();
            assertThat(second.isAlive()).isFalse();
            assertThat(Files.readString(log)).contains("FIRST-STDOUT", "SECOND-STDOUT", "SECOND-STDERR");
            if (second.supportsNormalTermination()) assertThat(Files.readString(log)).contains("SECOND-SHUTDOWN");
        }
        assertThat(second.isAlive()).isFalse();
        assertThat(Files.exists(log)).isFalse();
    }

    @Test void assertionFailureStillStopsTheChildAndDeletesOnlyItsOwnLog() throws Exception {
        var application = new RestartTestApplication(directory, "failure-cleanup-");
        Path unrelated = Files.writeString(directory.resolve("unrelated.log"), "keep");
        Process child = application.start(child("linger", "READY"));
        awaitOutput(application.logPath(), "READY-STDERR");
        var original = new AssertionError("original test assertion");
        assertThatThrownBy(() -> {
            try (application) { throw original; }
        }).isSameAs(original);
        assertThat(child.isAlive()).isFalse();
        assertThat(Files.exists(application.logPath())).isFalse();
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
    }

    @Test void failedSpawnClosesTheParentWriterAndAllowsLogDeletion() throws Exception {
        var application = new RestartTestApplication(directory, "failed-spawn-");
        assertThatThrownBy(() -> {
            try (application) { application.start(new ProcessBuilder(directory.resolve("missing-executable").toString())); }
        }).isInstanceOf(IOException.class);
        assertThat(Files.exists(application.logPath())).isFalse();
    }

    @Test void transientFilesystemContentionIsRetriedAndActuallyDeleted() throws Exception {
        Path log = Files.createTempFile(directory, "contention-", ".log");
        var attempts = new AtomicInteger();
        RestartTestApplication.deleteWithRetry(() -> {
            if (attempts.incrementAndGet() < 3) throw new FileSystemException(log.toString(), null, "simulated sharing violation");
            Files.deleteIfExists(log);
        }, 3, 0);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(Files.exists(log)).isFalse();
    }

    @Test void persistentContentionStillFailsAfterTheBoundedAttempts() {
        var attempts = new AtomicInteger();
        var failure = new FileSystemException("isolated-test-log", null, "simulated persistent sharing violation");
        assertThatThrownBy(() -> RestartTestApplication.deleteWithRetry(() -> {
            attempts.incrementAndGet(); throw failure;
        }, 3, 0)).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test void unrelatedIoFailureIsReportedWithoutRetry() {
        var attempts = new AtomicInteger();
        var failure = new IOException("simulated non-filesystem failure");
        assertThatThrownBy(() -> RestartTestApplication.deleteWithRetry(() -> {
            attempts.incrementAndGet(); throw failure;
        }, 3, 0)).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test void interruptedDeletionPreservesTheInterruptAndReportsFailure() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> RestartTestApplication.deleteWithRetry(() -> {
                throw new FileSystemException("isolated-test-log", null, "simulated sharing violation");
            }, 3, 0)).isInstanceOf(IOException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
