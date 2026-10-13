package com.medicalrag.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

public final class StorageException extends RuntimeException {
    public enum Code { CONFIGURATION, INVALID_REFERENCE, INVALID_NAME, EMPTY_FILE, TOO_LARGE,
        UNSUPPORTED_FORMAT, FORMAT_MISMATCH, DAMAGED_FILE, NOT_FOUND, ACCESS_DENIED, FILE_IN_USE,
        ALREADY_EXISTS, ATOMIC_COMMIT_UNSUPPORTED, IO_FAILURE, INTEGRITY_MISMATCH, CLEANUP_FAILURE }
    private final Code code;
    public StorageException(Code code, String message) { super(message); this.code = code; }
    public StorageException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }

    static StorageException io(IOException cause) {
        if (cause instanceof NoSuchFileException) return new StorageException(Code.NOT_FOUND, "原件不存在，请核对业务记录", cause);
        if (cause instanceof AtomicMoveNotSupportedException) return new StorageException(Code.ATOMIC_COMMIT_UNSUPPORTED, "存储文件系统不支持原子提交，请更换支持原子移动的本机目录", cause);
        if (cause instanceof FileAlreadyExistsException) return new StorageException(Code.ALREADY_EXISTS, "存储对象已存在，未覆盖任何原件", cause);
        if (cause instanceof AccessDeniedException) return new StorageException(Code.ACCESS_DENIED, "没有原件目录的操作权限，请检查本机权限", cause);
        if (cause instanceof FileSystemException fs && fs.getReason() != null) {
            String reason = fs.getReason().toLowerCase(Locale.ROOT);
            if (reason.contains("being used") || reason.contains("sharing violation") || reason.contains("占用") || reason.contains("正在使用")) {
                return new StorageException(Code.FILE_IN_USE, "原件正在被其他程序占用，请关闭使用它的程序后重试", cause);
            }
        }
        return new StorageException(Code.IO_FAILURE, "原件存储 I/O 操作失败，未报告成功，请检查本机日志", cause);
    }
    static StorageException unsafe() { return new StorageException(Code.INVALID_REFERENCE, "存储标识或路径不安全，拒绝操作"); }
}
