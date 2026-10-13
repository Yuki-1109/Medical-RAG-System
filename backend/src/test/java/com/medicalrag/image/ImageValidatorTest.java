package com.medicalrag.image;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.medicalrag.storage.OriginalType;
import static org.assertj.core.api.Assertions.*;
class ImageValidatorTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings={"jpg","png"}) void fullDecodeAndChannelClose(String format) throws Exception {
        Path file=root.resolve("fixture."+format); byte[] bytes=SyntheticImages.create(format,32,24); Files.write(file,bytes);
        try (var channel=FileChannel.open(file)) {
            assertThat(new ImageValidator(new ImageProperties(8192,8192,16000000)).validate(channel,format.equals("png") ? OriginalType.PNG : OriginalType.JPEG)).isEqualTo(new ImageValidator.Dimensions(32,24));
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes); Files.delete(file); assertThat(Files.exists(file)).isFalse();
    }
    @Test void dimensionOrPixelLimitsRejectBeforePixelAllocation() throws Exception {
        Path file=root.resolve("fixture.png"); Files.write(file,SyntheticImages.png());
        for (var limits : new ImageProperties[]{new ImageProperties(31,24,1000),new ImageProperties(32,23,1000),new ImageProperties(32,24,767)})
            try (var channel=FileChannel.open(file)) { assertThatThrownBy(() -> new ImageValidator(limits).validate(channel,OriginalType.PNG)).isInstanceOf(ImageException.class).hasMessageContaining("像素"); }
    }
    @Test void signatureOnlyOrTruncatedDataAndWrongFormatFail() throws Exception {
        Path file=root.resolve("bad.png"); byte[] bytes=SyntheticImages.png();
        Files.write(file,java.util.Arrays.copyOf(bytes,40));
        try (var channel=FileChannel.open(file)) { assertThatThrownBy(() -> new ImageValidator(new ImageProperties(100,100,10000)).validate(channel,OriginalType.PNG)).isInstanceOf(ImageException.class); }
        Files.write(file,bytes);
        try (var channel=FileChannel.open(file)) { assertThatThrownBy(() -> new ImageValidator(new ImageProperties(100,100,10000)).validate(channel,OriginalType.JPEG)).isInstanceOf(ImageException.class); }
    }
    @ParameterizedTest @ValueSource(strings={"LESION","NORMAL","UNCLASSIFIED"}) void plainManualValuesAreIndependentOfOriginal(String type) {
        assertThat(new ImageMetadata(type," Stage II ","mild","teaching","synthetic only").normalized().stage()).isEqualTo("Stage II");
    }
    @ParameterizedTest @ValueSource(strings={"<script>alert(1)</script>","a\u0000b"}) void htmlAndControlsRejected(String text) {
        assertThatThrownBy(() -> new ImageMetadata("LESION",text,"","","").normalized()).isInstanceOf(ImageException.class);
    }
    @Test void overlongAndUnknownTypesRejected() {
        assertThatThrownBy(() -> new ImageMetadata("AI_DIAGNOSED","","","","").normalized()).isInstanceOf(ImageException.class);
        assertThatThrownBy(() -> new ImageMetadata("NORMAL","x".repeat(101),"","","").normalized()).isInstanceOf(ImageException.class);
        assertThatThrownBy(() -> new ImageProperties(0,100,1000)).isInstanceOf(IllegalArgumentException.class);
    }
}
