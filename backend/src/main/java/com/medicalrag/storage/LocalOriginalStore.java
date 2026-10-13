package com.medicalrag.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** Package-private: business modules use the authenticated KnowledgeBaseFileStorage facade. */
final class LocalOriginalStore {
    private final StorageProperties properties;
    private final Path root;
    private final StorageIo io;
    private final Supplier<UUID> tokens;

    LocalOriginalStore(StorageProperties properties, Path root, StorageIo io) { this(properties, root, io, UUID::randomUUID); }
    LocalOriginalStore(StorageProperties properties, Path root, StorageIo io, Supplier<UUID> tokens) {
        this.properties = properties; this.root = root.toAbsolutePath().normalize(); this.io = io; this.tokens = tokens;
        try {
            ensureDirectory(this.root);
            ensureDirectory(this.root.resolve(".storage-objects"));
            ensureDirectory(this.root.resolve(".storage-staging"));
            if (!Files.isReadable(this.root) || !Files.isWritable(this.root)) throw new AccessDeniedException(this.root.toString());
        } catch (IOException | SecurityException exception) {
            throw new StorageException(StorageException.Code.CONFIGURATION, "存储初始化失败，请检查 APP_STORAGE_ROOT 的路径和权限；未回退到临时目录", exception);
        }
    }
    private void ensureDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = directory.getParent();
            if (parent != null) ensureDirectory(parent);
            try { io.createDirectory(directory); } catch (FileAlreadyExistsException raced) { /* validate below */ }
        }
        checkDirectory(directory);
    }
    private static void checkDirectory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw StorageException.unsafe();
    }
    private void checkChain(Path directory) throws IOException {
        if (!directory.toAbsolutePath().normalize().startsWith(root)) throw StorageException.unsafe();
        // Revalidate the root and ancestors too: a root renamed into a link is invalid.
        for (Path current = directory; current != null; current = current.getParent()) checkDirectory(current);
    }
    private Path objectDirectory(StorageKey key) {
        return root.resolve(".storage-objects").resolve(key.namespace()).resolve(Long.toString(key.knowledgeBaseId())).resolve(key.token().toString());
    }
    private StorageKey checkedKey(StorageScope scope, StoredOriginal receipt) {
        if (scope == null || receipt == null) throw StorageException.unsafe();
        StorageKey key = StorageKey.parse(receipt.storageKey());
        if (key.knowledgeBaseId() != scope.knowledgeBaseId || key.type() != receipt.type()) throw StorageException.unsafe();
        return key;
    }
    private Path checkedFile(StorageKey key) throws IOException {
        Path directory = objectDirectory(key);
        checkChain(directory);
        Path file = directory.resolve("original." + key.type().extension());
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || !file.toRealPath().equals(file.toAbsolutePath().normalize())) throw StorageException.unsafe();
        return file;
    }
    static String validateName(String value, OriginalType type) {
        if (value == null || type == null || value.isBlank() || value.length() > 255
                || value.contains("/") || value.contains("\\") || value.contains(":")
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new StorageException(StorageException.Code.INVALID_NAME, "原始文件名无效，不能包含路径或控制字符");
        }
        String name = value.strip();
        int separator = name.lastIndexOf('.');
        if (separator < 1) throw new StorageException(StorageException.Code.FORMAT_MISMATCH, "文件名必须使用与内容对应的 PDF、JPG/JPEG 或 PNG 扩展名");
        String extension = name.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals(type.extension()) && !(type == OriginalType.JPEG && extension.equals("jpeg"))) {
            throw new StorageException(StorageException.Code.FORMAT_MISMATCH, "文件扩展名与预期原件类型不一致");
        }
        return name;
    }
    StoredOriginal save(StorageScope scope, String originalName, OriginalType expected, InputStream source) {
        return saveReserved(scope, reserve(scope, expected), originalName, source);
    }
    StorageReservation reserve(StorageScope scope, OriginalType type) {
        if (scope == null || type == null) throw StorageException.unsafe();
        return new StorageReservation(new StorageKey(type.namespace(), scope.knowledgeBaseId, tokens.get(), type).value(), type);
    }
    StoredOriginal saveReserved(StorageScope scope, StorageReservation reservation, String originalName, InputStream source) {
        if (scope == null || source == null) throw StorageException.unsafe();
        StorageKey reserved = reservationKey(scope, reservation);
        OriginalType expected = reservation.type();
        String name = validateName(originalName, expected);
        Path stage = null;
        boolean stageOwned = false;
        try {
            UUID token = reserved.token();
            var key = reserved;
            Path parent = objectDirectory(key).getParent();
            ensureDirectory(parent); checkChain(parent);
            Path stages = root.resolve(".storage-staging"); checkChain(stages);
            stage = stages.resolve(token.toString());
            io.createDirectory(stage); stageOwned = true; checkChain(stage);
            Path stagedFile = stage.resolve("original." + expected.extension());
            MessageDigest digest = digest();
            long size = 0, limit = expected == OriginalType.PDF ? properties.maxPdfBytes() : properties.maxImageBytes();
            byte[] bytes = new byte[8192];
            try (var output = io.createFile(stagedFile)) {
                for (int count; (count = source.read(bytes)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Original write interrupted");
                    if (count == 0) continue;
                    if (count > limit - size) throw new StorageException(StorageException.Code.TOO_LARGE, "文件超过本机配置的大小上限，未保存原件");
                    size += count; digest.update(bytes, 0, count);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
                    while (buffer.hasRemaining()) output.write(buffer);
                }
                output.force(true);
            }
            if (size == 0) throw new StorageException(StorageException.Code.EMPTY_FILE, "空文件不能保存为原件");
            checkChain(stage);
            OriginalType detected = OriginalFormatValidator.validate(stagedFile, expected);
            checkChain(parent); checkChain(stage);
            var receipt = new StoredOriginal(key.value(), size, HexFormat.of().formatHex(digest.digest()), detected, name);
            io.publish(stage, objectDirectory(key));
            stageOwned = false;
            return receipt;
        } catch (IOException exception) {
            StorageException failure = StorageException.io(exception);
            if (stageOwned) compensate(stage, expected, failure);
            throw failure;
        } catch (RuntimeException exception) {
            if (stageOwned) compensate(stage, expected, exception);
            throw exception;
        }
    }
    private StorageKey reservationKey(StorageScope scope, StorageReservation reservation) {
        if (scope == null || reservation == null) throw StorageException.unsafe();
        StorageKey key = StorageKey.parse(reservation.storageKey());
        if (key.knowledgeBaseId() != scope.knowledgeBaseId || key.type() != reservation.type()) throw StorageException.unsafe();
        return key;
    }
    /** Only for a persisted incomplete write intent, never for an active business resource. */
    void discardReservation(StorageScope scope, StorageReservation reservation) {
        StorageKey key = reservationKey(scope, reservation);
        try {
            Path objects = root.resolve(".storage-objects"); checkChain(objects);
            Path directory = objectDirectory(key);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                checkChain(directory);
                Path candidate = directory.resolve("original." + key.type().extension());
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) io.deleteFile(checkedFile(key));
            }
            Path stages = root.resolve(".storage-staging"); checkChain(stages);
            Path stage = stages.resolve(key.token().toString());
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                checkChain(stage);
                Path file = stage.resolve("original." + key.type().extension());
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw StorageException.unsafe();
                    io.deleteFile(file);
                }
                Files.delete(stage); // Exact owned intent only; unknown contents cause a visible failure.
            }
        } catch (IOException exception) { throw StorageException.io(exception); }
    }
    <T> T inspect(StorageScope scope, StoredOriginal receipt, OriginalInspection<T> inspection) {
        StorageKey key = checkedKey(scope, receipt);
        if (inspection == null) throw StorageException.unsafe();
        try {
            Path file = checkedFile(key);
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                verify(channel, receipt); channel.position(0);
                return inspection.inspect(channel);
            }
        } catch (IOException exception) { throw StorageException.io(exception); }
    }
    private void compensate(Path stage, OriginalType type, RuntimeException failure) {
        try {
            checkChain(stage);
            Path file = stage.resolve("original." + type.extension());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw StorageException.unsafe();
                io.deleteFile(file);
            }
            // Remove only our exact directory if empty. Never recursively delete unknown files.
            Files.delete(stage);
        } catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(new StorageException(StorageException.Code.CLEANUP_FAILURE, "临时原件清理失败，需要按本机错误记录处理，不可清空存储目录", cleanup));
        }
    }
    void copyTo(StorageScope scope, StoredOriginal receipt, OutputStream destination) {
        StorageKey key = checkedKey(scope, receipt);
        if (destination == null) throw StorageException.unsafe();
        try {
            Path file = checkedFile(key);
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                verify(channel, receipt);
                channel.position(0);
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                for (int count; (count = channel.read(buffer)) != -1;) {
                    destination.write(buffer.array(), 0, count); buffer.clear();
                }
            }
        } catch (IOException exception) { throw StorageException.io(exception); }
    }
    void delete(StorageScope scope, StoredOriginal receipt) {
        StorageKey key = checkedKey(scope, receipt);
        try {
            Path file = checkedFile(key);
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { verify(channel, receipt); }
            checkedFile(key); // Recheck containment immediately before deletion; stream already closed.
            io.deleteFile(file);
            // Object directories are retained; deleting an original never deletes a library directory.
        } catch (IOException exception) { throw StorageException.io(exception); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("JDK SHA-256 unavailable", impossible); }
    }
    private static void verify(FileChannel channel, StoredOriginal receipt) throws IOException {
        MessageDigest digest = digest(); long size = 0;
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        for (int count; (count = channel.read(buffer)) != -1;) {
            size += count; digest.update(buffer.array(), 0, count); buffer.clear();
        }
        if (size != receipt.size() || !HexFormat.of().formatHex(digest.digest()).equals(receipt.sha256())) {
            throw new StorageException(StorageException.Code.INTEGRITY_MISMATCH, "原件大小或 SHA-256 与业务凭据不一致，拒绝读取或删除");
        }
    }
}
