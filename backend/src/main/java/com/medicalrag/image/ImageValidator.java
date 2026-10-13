package com.medicalrag.image;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import com.medicalrag.storage.OriginalType;
@Component
@EnableConfigurationProperties(ImageProperties.class)
public class ImageValidator {
    private final ImageProperties limits;
    public ImageValidator(ImageProperties limits) { this.limits=limits; }
    public record Dimensions(int width,int height) {}
    public Dimensions validate(SeekableByteChannel channel,OriginalType type) throws IOException {
        try (var input=new ChannelImageInput(channel)) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw damaged();
            ImageReader reader=readers.next();
            try {
                String format=reader.getFormatName();
                if (!(type==OriginalType.JPEG && format.equalsIgnoreCase("JPEG")) && !(type==OriginalType.PNG && format.equalsIgnoreCase("PNG"))) throw damaged();
                reader.setInput(input,false,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                // Read dimensions BEFORE allocating pixels; multiplication is long, not int.
                if (width<=0 || height<=0) throw damaged();
                if (width>limits.maxWidth() || height>limits.maxHeight() || (long)width*height>limits.maxPixels())
                    throw new ImageException(400,"IMAGE_DIMENSIONS_TOO_LARGE","图片尺寸或总像素超过本机上限，请使用较小的教学图片");
                boolean[] warned={false}; reader.addIIOReadWarningListener((source,warning) -> warned[0]=true);
                var decoded=reader.read(0);
                if (decoded==null) throw damaged();
                try {
                    if (warned[0] || decoded.getWidth()!=width || decoded.getHeight()!=height) throw damaged();
                    return new Dimensions(width,height);
                } finally { decoded.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException | IndexOutOfBoundsException | IllegalArgumentException error) { throw damaged(); }
    }
    private static ImageException damaged() { return new ImageException(400,"DAMAGED_IMAGE","图片无法完整解码或内容损坏，请用可信查看器检查原件后重试"); }
}
