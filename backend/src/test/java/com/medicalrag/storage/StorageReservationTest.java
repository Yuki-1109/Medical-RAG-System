package com.medicalrag.storage;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StorageReservationTest {
    @TempDir Path temporary;
    final StorageScope scope=new StorageScope(31);
    LocalOriginalStore store() { Path root=temporary.resolve("私有 storage"); return new LocalOriginalStore(new StorageProperties(root.toString(),52428800,20971520),root,new StorageIo()); }
    @Test void reservationUsesServerUuidAndCannotOverwriteACommittedOriginal() throws Exception {
        var store=store(); var intent=store.reserve(scope,OriginalType.PDF);
        var receipt=store.saveReserved(scope,intent,"合成.pdf",new ByteArrayInputStream(OriginalTestFiles.pdf()));
        assertThat(receipt.storageKey()).isEqualTo(intent.storageKey());
        assertThatThrownBy(() -> store.saveReserved(scope,intent,"合成.pdf",new ByteArrayInputStream(OriginalTestFiles.pdf()))).isInstanceOf(StorageException.class);
        store.inspect(scope,receipt,channel -> { assertThat(channel.size()).isEqualTo(receipt.size()); return null; });
        store.delete(scope,receipt);
    }
    @Test void reservationCleanupIsScopedAndIdempotentIncludingAnAlreadyRemovedFile() throws Exception {
        var store=store(); var intent=store.reserve(scope,OriginalType.PDF);
        store.discardReservation(scope,intent);
        store.saveReserved(scope,intent,"合成.pdf",new ByteArrayInputStream(OriginalTestFiles.pdf()));
        assertThatThrownBy(() -> store.discardReservation(new StorageScope(32),intent)).isInstanceOf(StorageException.class);
        store.discardReservation(scope,intent); store.discardReservation(scope,intent);
    }
    @Test void inspectionIsReadOnlyAndClosesItsHandleOnFailure() throws Exception {
        var store=store(); var receipt=store.save(scope,"合成.pdf",OriginalType.PDF,new ByteArrayInputStream(OriginalTestFiles.pdf()));
        assertThatThrownBy(() -> store.inspect(scope,receipt,channel -> { channel.write(ByteBuffer.wrap(new byte[] {1})); return null; })).isInstanceOf(NonWritableChannelException.class);
        assertThatThrownBy(() -> store.inspect(scope,receipt,channel -> { throw new IllegalStateException("Synthetic parser failure"); })).isInstanceOf(IllegalStateException.class);
        store.delete(scope,receipt); // Windows cannot delete a leaked parser file handle.
    }
    @Test void untrustedReservationsNeverAcceptArbitraryPathsOrTypeChanges() {
        for (String path : new String[] {"../original.pdf","C:\\private.pdf","\\\\host\\private.pdf","/tmp/private.pdf"}) assertThatThrownBy(() -> new StorageReservation(path,OriginalType.PDF)).isInstanceOf(StorageException.class);
        var intent=store().reserve(scope,OriginalType.PDF);
        assertThatThrownBy(() -> new StorageReservation(intent.storageKey(),OriginalType.PNG)).isInstanceOf(StorageException.class);
    }
}
