package com.medicalrag.document;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PdfValidatorTest {
    @TempDir Path temporary;
    int validate(byte[] pdf) throws Exception {
        Path file=Files.createTempFile(temporary,"synthetic-",".pdf"); Files.write(file,pdf);
        try (var channel=FileChannel.open(file,StandardOpenOption.READ)) { return new PdfValidator().pageCount(channel); }
        finally { Files.delete(file); } // Also verifies parser/reader handle release before deletion.
    }
    @Test void realEnglishPdfHasActualPagesWithoutTextExtraction() throws Exception { assertThat(validate(SyntheticPdfs.text(3))).isEqualTo(3); }
    @Test void aScanIsAcceptedWithoutPretendingItWasProcessed() throws Exception { assertThat(validate(SyntheticPdfs.scan())).isEqualTo(1); }
    @Test void encryptedPdfHasReadablePasswordError() throws Exception {
        assertThatThrownBy(() -> validate(SyntheticPdfs.encrypted())).isInstanceOf(DocumentException.class).hasMessageContaining("密码");
    }
    @Test void damagedStructureAndZeroPagePdfAreRejected() throws Exception {
        assertThatThrownBy(() -> validate("%PDF-1.7\nnot a catalog\n%%EOF".getBytes())).isInstanceOf(DocumentException.class).hasMessageContaining("损坏");
        assertThatThrownBy(() -> validate(SyntheticPdfs.text(0))).isInstanceOf(DocumentException.class);
        byte[] bad=new String(SyntheticPdfs.text(1),java.nio.charset.StandardCharsets.ISO_8859_1).replace("/Type /Catalog","/Type /Missing").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> validate(bad)).isInstanceOf(DocumentException.class).hasMessageContaining("损坏");
    }
    @Test void bufferedReaderSupportsSeekViewsAndReleasesOnlyItsWrapper() throws Exception {
        Path file=temporary.resolve("buffered.pdf"); byte[] data=new byte[25000];
        for (int i=0;i<data.length;i++) data[i]=(byte)i; Files.write(file,data);
        try (var channel=FileChannel.open(file,StandardOpenOption.READ); var reader=new ChannelPdfReader(channel)) {
            reader.seek(8191); byte[] bytes=new byte[9000]; assertThat(reader.read(bytes)).isEqualTo(9000);
            assertThat(bytes).isEqualTo(java.util.Arrays.copyOfRange(data,8191,17191));
            try (var view=reader.createView(10,20)) { assertThat(view.read()).isEqualTo(10); view.seek(19); assertThat(view.read()).isEqualTo(29); assertThat(view.read()).isEqualTo(-1); }
            reader.seek(data.length); assertThat(reader.isEOF()).isTrue(); assertThat(reader.read()).isEqualTo(-1);
            assertThatThrownBy(() -> reader.seek(-1)).isInstanceOf(IOException.class);
            reader.close(); assertThat(channel.isOpen()).isTrue(); assertThatThrownBy(reader::read).isInstanceOf(IOException.class);
        }
        Files.delete(file);
    }
    @Test void originalNamesAndIdsRejectPathAndHeaderInjection() {
        assertThat(DocumentService.filename(" 合成 research \"v1\".PDF ")).isEqualTo("合成 research \"v1\".PDF");
        for (String name : new String[] {"../x.pdf","C:\\x.pdf","\\\\host\\x.pdf","x\r\n.pdf","x.png",".pdf",""}) assertThatThrownBy(() -> DocumentService.filename(name)).isInstanceOf(DocumentException.class);
        for (String id : new String[] {"0","01","../1","9223372036854775808","-1"}) assertThatThrownBy(() -> DocumentService.parseId(id)).isInstanceOf(DocumentException.class);
    }
}
