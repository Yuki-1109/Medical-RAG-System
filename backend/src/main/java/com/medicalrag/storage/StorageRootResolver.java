package com.medicalrag.storage;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class StorageRootResolver {
    private StorageRootResolver() { }
    static Path resolve(String configured, Path workingDirectory) {
        try {
            Path input = Path.of(configured);
            for (Path part : input) if (part.toString().equals("..")) throw StorageException.unsafe();
            // Foreign/drive-relative Windows syntax must never become a Linux filename.
            if (!input.isAbsolute() && (configured.contains(":") || configured.contains("\\"))) throw StorageException.unsafe();
            Path repository = repository(workingDirectory);
            if (!input.isAbsolute() && repository == null) {
                throw new StorageException(StorageException.Code.CONFIGURATION, "无法定位项目根目录，请将 APP_STORAGE_ROOT 设置为绝对路径");
            }
            Path root = (input.isAbsolute() ? input : repository.resolve(input)).toAbsolutePath().normalize();
            if (root.getParent() == null) throw StorageException.unsafe();
            // Absolute paths may point into a repository different from the current
            // working directory. Protect that repository's public/classpath trees too.
            Path owningRepository = repository(root);
            Path protectedRepository = owningRepository == null ? repository : owningRepository;
            if (protectedRepository != null && (root.equals(protectedRepository) || root.equals(protectedRepository.resolve("backend"))
                    || root.startsWith(protectedRepository.resolve("frontend")) || root.startsWith(protectedRepository.resolve("backend/src"))
                    || root.startsWith(protectedRepository.resolve("backend/target/classes")))) {
                throw new StorageException(StorageException.Code.CONFIGURATION, "原件不能存放在项目根目录、前端目录或后端源代码/静态资源目录");
            }
            return root;
        } catch (InvalidPathException exception) {
            throw new StorageException(StorageException.Code.CONFIGURATION, "APP_STORAGE_ROOT 路径无效", exception);
        }
    }
    static Path repository(Path workingDirectory) {
        for (Path candidate = workingDirectory.toAbsolutePath().normalize(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("backend/pom.xml")) && Files.isRegularFile(candidate.resolve("frontend/package.json"))) return candidate;
        }
        return null;
    }
}
