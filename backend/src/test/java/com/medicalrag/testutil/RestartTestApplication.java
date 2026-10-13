package com.medicalrag.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns only a test child JVM and its uniquely generated temporary log. */
public final class RestartTestApplication implements AutoCloseable {
    private final Path log;
    private Process process;
    private OutputStream logOutput;
    private FutureTask<Void> copy;
    private Thread logger;

    public RestartTestApplication(String prefix) throws IOException { this(Path.of("target"), prefix); }

    public RestartTestApplication(Path directory, String prefix) throws IOException {
        log = Files.createTempFile(directory, prefix, ".log");
    }

    public Path logPath() { return log; }

    public Process start(ProcessBuilder builder) throws IOException {
        if (process != null) throw new IllegalStateException("Stop the previous test application before restarting");
        logOutput = Files.newOutputStream(log, StandardOpenOption.APPEND);
        try {
            // Never give a Windows child an inherited handle to the log file. Only
            // the parent logger owns that file, and completion proves it was closed.
            process = builder.redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE).redirectErrorStream(true).start();
            InputStream stdout = process.getInputStream();
            OutputStream sink = logOutput;
            copy = new FutureTask<>(() -> {
                try (stdout; sink) { stdout.transferTo(sink); }
                return null;
            });
            logger = Thread.ofPlatform().daemon(true).name("restart-test-log-" + process.pid()).start(copy);
            process.getOutputStream().close(); // The application does not need stdin.
            return process;
        } catch (IOException | RuntimeException failure) {
            try {
                if (process != null) stop();
                else logOutput.close();
            } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void stop() throws IOException {
        if (process == null) return;
        List<IOException> failures = new ArrayList<>();
        boolean interrupted = false;
        try {
            // Process.destroy() also closes Java's stdout pipe while the copier may
            // still be reading. Signal via ProcessHandle; retain the pipe until EOF.
            process.toHandle().destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.toHandle().destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) failures.add(new IOException("Test application did not exit after forced termination"));
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            failures.add(new IOException("Interrupted while stopping test application", exception));
            process.toHandle().destroyForcibly();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) failures.add(new IOException("Interrupted test application did not terminate"));
            } catch (InterruptedException second) {
                failures.add(new IOException("Interrupted again while terminating test application", second));
            }
        }
        try {
            if (copy != null) copy.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            failures.add(new IOException("Failed to capture test application output", exception.getCause()));
        } catch (TimeoutException exception) {
            failures.add(new IOException("Test output did not finish after application exit", exception));
        } catch (InterruptedException exception) {
            interrupted = true;
            failures.add(new IOException("Interrupted while draining test application output", exception));
        }
        // Also close explicitly on unsuccessful copy/termination. These are only
        // this fixture's streams; no existing logs, data or database are touched.
        closeStream(process.getOutputStream(), failures);
        closeStream(process.getInputStream(), failures);
        closeStream(process.getErrorStream(), failures);
        closeStream(logOutput, failures);
        if (logger != null) {
            logger.interrupt();
            try { logger.join(5000); }
            catch (InterruptedException exception) {
                interrupted = true;
                failures.add(new IOException("Interrupted while joining test logger", exception));
            }
            if (logger.isAlive()) failures.add(new IOException("Test logger did not terminate"));
        }
        process = null;
        copy = null;
        logger = null;
        logOutput = null;
        if (interrupted) Thread.currentThread().interrupt();
        throwFailures(failures);
    }

    private static void closeStream(java.io.Closeable stream, List<IOException> failures) {
        if (stream == null) return;
        try { stream.close(); }
        catch (IOException exception) { failures.add(exception); }
    }

    @Override public void close() throws IOException {
        List<IOException> failures = new ArrayList<>();
        try { stop(); } catch (IOException exception) { failures.add(exception); }
        try { deleteWithRetry(() -> Files.deleteIfExists(log), 50, 100); }
        catch (IOException exception) { failures.add(exception); }
        throwFailures(failures);
    }

    @FunctionalInterface interface DeleteAction { void run() throws IOException; }

    static void deleteWithRetry(DeleteAction action, int attempts, long pauseMillis) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try { action.run(); return; }
            catch (FileSystemException exception) {
                // Windows indexing/virus scanners may briefly retain a handle even
                // after our logger closed it. Retry only filesystem contention,
                // at most 4.9 seconds. Persistent failure still fails the test.
                if (attempt + 1 >= attempts) throw exception;
                try { Thread.sleep(pauseMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    var failure = new IOException("Interrupted while deleting test log", interrupted);
                    failure.addSuppressed(exception);
                    throw failure;
                }
            }
        }
    }

    private static void throwFailures(List<IOException> failures) throws IOException {
        if (failures.isEmpty()) return;
        IOException first = failures.getFirst();
        failures.stream().skip(1).forEach(first::addSuppressed);
        throw first;
    }
}
