package com.medicalrag.settings;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.medicalrag.storage.StorageProperties;
import org.springframework.stereotype.Component;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

@Component
class ModelKeyStore {
    private final ModelProperties properties;
    private final StorageProperties storage;
    private final ModelRepository repository;
    private final SecureRandom random=new SecureRandom();
    ModelKeyStore(ModelProperties properties, StorageProperties storage, ModelRepository repository) {
        this.properties=properties; this.storage=storage; this.repository=repository;
    }
    private Path resolve(String name) {
        Path input=Path.of(name);
        if (input.isAbsolute()) return input.normalize();
        Path root=Path.of("").toAbsolutePath();
        for (Path p=root; p!=null; p=p.getParent()) {
            if (Files.isRegularFile(p.resolve("backend/pom.xml")) && Files.isRegularFile(p.resolve("frontend/package.json"))) return p.resolve(input).normalize();
        }
        throw ModelException.key();
    }
    Path path() {
        try {
            Path file=resolve(properties.keyFile()); Path original=resolve(storage.root());
            // Only a private secrets directory, never a source tree or original-file namespace.
            if (!file.getFileName().toString().endsWith(".key") || file.getParent()==null
                    || !file.getParent().getFileName().toString().equals("secrets")
                    || file.startsWith(original) || original.startsWith(file.getParent())) throw ModelException.key();
            for (Path part=file; part!=null; part=part.getParent()) if (Files.isSymbolicLink(part)) throw ModelException.key();
            for (Path part=file; part!=null; part=part.getParent()) {
                String name=part.getFileName()==null?"":part.getFileName().toString();
                if (Set.of("frontend","src","target",".git",".storage-objects",".storage-staging").contains(name)) throw ModelException.key();
            }
            return file;
        } catch (ModelException e) { throw e; } catch (RuntimeException e) { throw ModelException.key(); }
    }
    private void protect(Path path, boolean directory, boolean newlyCreated) throws Exception {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,NOFOLLOW_LINKS);
        if (posix!=null) {
            var permissions=PosixFilePermissions.fromString(directory?"rwx------":"rw-------");
            if (newlyCreated) posix.setPermissions(permissions);
            var attrs=posix.readAttributes();
            if (!attrs.permissions().equals(permissions) || !attrs.owner().equals(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name")))) throw ModelException.key();
        } else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,NOFOLLOW_LINKS);
            if (acl==null) throw ModelException.key();
            var owner=acl.getOwner();
            if (newlyCreated) {
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                // NTFS DACL inheritance must also be disabled; Java's ACL view alone doesn't set SE_DACL_PROTECTED.
                Process process=new ProcessBuilder("icacls.exe",path.toString(),"/inheritance:r","/grant:r",
                    owner.getName()+":"+(directory?"(OI)(CI)F":"F"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw ModelException.key(); }
                if (process.exitValue()!=0) throw ModelException.key();
            }
            if (acl.getAcl().stream().anyMatch(entry -> entry.type()==AclEntryType.ALLOW && !entry.principal().equals(owner))) throw ModelException.key();
        }
    }
    private synchronized byte[] master(boolean create) {
        try {
            Path file=path(), directory=file.getParent();
            if (!Files.exists(file,NOFOLLOW_LINKS)) {
                if (!create || repository.hasEncryptedKeys()) throw ModelException.key();
                if (!Files.exists(directory,NOFOLLOW_LINKS)) {
                    // Parent must exist: never create a chain of publicly accessible directories.
                    var attrs=Files.getFileAttributeView(directory.getParent(),PosixFileAttributeView.class)!=null
                        ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}:new FileAttribute<?>[0];
                    Files.createDirectory(directory,attrs); protect(directory,true,true);
                }
                protect(directory,true,false);
                var attrs=Files.getFileAttributeView(directory,PosixFileAttributeView.class)!=null
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}:new FileAttribute<?>[0];
                try (var channel=FileChannel.open(file,Set.of(CREATE_NEW,WRITE,NOFOLLOW_LINKS),attrs)) {
                    protect(file,false,true);
                    byte[] generated=new byte[32]; random.nextBytes(generated);
                    try { var buffer=ByteBuffer.wrap(generated); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
                    finally { Arrays.fill(generated,(byte)0); }
                } catch (FileAlreadyExistsException e) { /* Another process created it; verify instead of replacing. */ }
            }
            if (!Files.isDirectory(directory,NOFOLLOW_LINKS) || !Files.isRegularFile(file,NOFOLLOW_LINKS)) throw ModelException.key();
            protect(directory,true,false); protect(file,false,false);
            try (var channel=FileChannel.open(file,READ,NOFOLLOW_LINKS)) {
                if (channel.size()!=32) throw ModelException.key();
                byte[] key=new byte[32]; var buffer=ByteBuffer.wrap(key);
                while (buffer.hasRemaining()) if (channel.read(buffer)<0) throw ModelException.key();
                return key;
            }
        } catch (ModelException e) { throw e; } catch (Exception e) { throw ModelException.key(); }
    }
    private byte[] aad(long owner, ModelTypes.Type type) { return ("medical-rag:v1:"+owner+":"+type.name()).getBytes(StandardCharsets.UTF_8); }
    String encrypt(String value, long owner, ModelTypes.Type type) {
        byte[] key=master(true);
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce)); cipher.updateAAD(aad(owner,type));
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) { throw ModelException.key(); } finally { Arrays.fill(key,(byte)0); }
    }
    String decrypt(String value, long owner, ModelTypes.Type type) {
        byte[] key=master(false);
        try {
            String[] parts=value.split(":",-1);
            if (parts.length!=3 || !parts[0].equals("v1")) throw ModelException.decrypt();
            byte[] nonce=Base64.getDecoder().decode(parts[1]); if (nonce.length!=12) throw ModelException.decrypt();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce)); cipher.updateAAD(aad(owner,type));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch (Exception e) { throw ModelException.decrypt(); } finally { Arrays.fill(key,(byte)0); }
    }
}
