package com.medicalrag.storage;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StorageConfigurationTest {
    @TempDir Path temporary;
    Path repository() throws Exception {
        Path root = temporary.resolve("中文项目 with spaces");
        Files.createDirectories(root.resolve("backend")); Files.createDirectories(root.resolve("frontend"));
        Files.writeString(root.resolve("backend/pom.xml"), "synthetic repository marker");
        Files.writeString(root.resolve("frontend/package.json"), "{}");
        return root;
    }
    @Test void rootIsStableFromRepositoryAndBackendWorkingDirectories() throws Exception {
        Path project = repository();
        assertThat(StorageRootResolver.resolve("data/originals", project)).isEqualTo(project.resolve("data/originals"));
        assertThat(StorageRootResolver.resolve("data/originals", project.resolve("backend"))).isEqualTo(project.resolve("data/originals"));
        assertThat(StorageRootResolver.resolve(temporary.resolve("私有原件 path").toString(), project)).isEqualTo(temporary.resolve("私有原件 path"));
    }
    @Test void publicDirectoriesAndAmbiguousRelativeRootsAreRejected() throws Exception {
        Path project = repository();
        for (String root : new String[] {"frontend/public", "backend/src/main/resources/static", "backend/target/classes/public", "..", project.toString()}) {
            assertThatThrownBy(() -> StorageRootResolver.resolve(root, project)).isInstanceOf(StorageException.class);
        }
        assertThatThrownBy(() -> StorageRootResolver.resolve("data/originals", temporary)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> StorageRootResolver.resolve(project.resolve("frontend/public").toString(), temporary)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> StorageRootResolver.resolve(project.resolve("backend/target/classes/static").toString(), temporary)).isInstanceOf(StorageException.class);
        if (!System.getProperty("os.name").startsWith("Windows")) {
            for (String root : new String[] {"C:\\private\\files", "C:/private/files", "\\\\server\\share"}) assertThatThrownBy(() -> StorageRootResolver.resolve(root, project)).isInstanceOf(StorageException.class);
        }
    }
    @Test void badLimitsAndRootsNeverSilentlyUseATemporaryDirectory() {
        for (long limit : new long[] {0, -1}) assertThatThrownBy(() -> new StorageProperties("data/originals", limit, 20)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> new StorageProperties("", 50, 20)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> new StorageProperties(null, 50, 20)).isInstanceOf(StorageException.class);
    }
    @Test void initializationPreservesExistingFilesAndRejectsAFileAsRoot() throws Exception {
        Path root = temporary.resolve("existing"); Files.createDirectories(root);
        Path previous = Files.writeString(root.resolve("unknown-private-file.txt"), "must survive");
        new LocalOriginalStore(new StorageProperties(root.toString(), 50, 20), root, new StorageIo());
        assertThat(Files.readString(previous)).isEqualTo("must survive");
        assertThatThrownBy(() -> new LocalOriginalStore(new StorageProperties(previous.toString(), 50, 20), previous, new StorageIo())).isInstanceOf(StorageException.class);
    }
}
