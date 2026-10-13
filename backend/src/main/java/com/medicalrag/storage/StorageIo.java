package com.medicalrag.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Small filesystem boundary used for deterministic failure tests against real temp files. */
class StorageIo {
    void createDirectory(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else Files.createDirectory(path);
    }
    FileChannel createFile(Path path) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return FileChannel.open(path, options);
    }
    void publish(Path stageDirectory, Path objectDirectory) throws IOException {
        if (Files.exists(objectDirectory, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(objectDirectory.toString());
        // Publish the complete nonempty directory, not a single file. Existing valid
        // objects are nonempty directories and cannot be overwritten by rename.
        Files.move(stageDirectory, objectDirectory, StandardCopyOption.ATOMIC_MOVE);
    }
    void deleteFile(Path path) throws IOException { Files.delete(path); }
}
