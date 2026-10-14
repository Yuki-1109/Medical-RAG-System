package com.medicalrag.settings;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import com.medicalrag.storage.StorageProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelKeyStoreTest {
    @TempDir Path root;
    ModelRepository repository;
    ModelKeyStore keys;
    Path file;
    @BeforeEach void setup() {
        repository=mock(ModelRepository.class); file=root.resolve("secrets/master.key"); keys=store(file);
    }
    ModelKeyStore store(Path path) { return new ModelKeyStore(new ModelProperties(path.toString(),false,500),new StorageProperties(root.resolve("originals").toString(),1,1),repository); }
    @Test void encryptsWithRandomNonceAndPersistsAcrossNewComponentWithoutPlaintext() throws Exception {
        String first=keys.encrypt("synthetic-test-key",1,ModelTypes.Type.CHAT);
        String second=keys.encrypt("synthetic-test-key",1,ModelTypes.Type.CHAT);
        assertThat(first).startsWith("v1:").doesNotContain("synthetic-test-key").isNotEqualTo(second);
        assertThat(Files.size(file)).isEqualTo(32);
        assertThat(store(file).decrypt(first,1,ModelTypes.Type.CHAT)).isEqualTo("synthetic-test-key");
    }
    @Test void missingMasterNeverRecreatedWhenEncryptedRowsExist() {
        when(repository.hasEncryptedKeys()).thenReturn(true);
        assertThatThrownBy(() -> keys.encrypt("replacement",1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        assertThat(file).doesNotExist();
    }
    @Test void deletedMasterAfterSaveFailsAndDoesNotGenerateReplacement() throws Exception {
        String cipher=keys.encrypt("synthetic",1,ModelTypes.Type.CHAT); Files.delete(file);
        assertThatThrownBy(() -> store(file).decrypt(cipher,1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        assertThat(file).doesNotExist();
    }
    @Test void damagedMasterFailsWithoutOverwriting() throws Exception {
        keys.encrypt("synthetic",1,ModelTypes.Type.CHAT); Files.write(file,new byte[3]);
        assertThatThrownBy(() -> store(file).encrypt("replacement",1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        assertThat(Files.size(file)).isEqualTo(3);
    }
    @Test void wrongMasterAndTamperedCipherAndDifferentOwnerOrTypeFailAuthentication() throws Exception {
        String cipher=keys.encrypt("synthetic",1,ModelTypes.Type.CHAT);
        assertThatThrownBy(() -> keys.decrypt(cipher,2,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> keys.decrypt(cipher,1,ModelTypes.Type.EMBEDDING)).isInstanceOf(ModelException.class);
        String[] parts=cipher.split(":");byte[] payload=Base64.getDecoder().decode(parts[2]);payload[0]^=1;
        String changed=parts[0]+":"+parts[1]+":"+Base64.getEncoder().encodeToString(payload);
        assertThatThrownBy(() -> keys.decrypt(changed,1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        byte[] wrong=new byte[32];Arrays.fill(wrong,(byte)7);Files.write(file,wrong);
        assertThatThrownBy(() -> store(file).decrypt(cipher,1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
    }
    @Test void insecurePermissionsFailRatherThanSilentlyRepairingExistingKey() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"),"POSIX权限测试，Windows由ACL验收");
        String cipher=keys.encrypt("synthetic",1,ModelTypes.Type.CHAT);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> keys.decrypt(cipher,1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
    }
    @Test void symbolicLinkAndPublicOrOriginalPathsRejected() throws Exception {
        assertThatThrownBy(() -> store(root.resolve("frontend/secrets/master.key")).encrypt("x",1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> store(root.resolve("originals/secrets/master.key")).encrypt("x",1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"),"需要符号链接权限");
        Path target=root.resolve("other");Files.createDirectory(target);Files.createSymbolicLink(file.getParent(),target);
        assertThatThrownBy(() -> keys.encrypt("x",1,ModelTypes.Type.CHAT)).isInstanceOf(ModelException.class);
    }
}
