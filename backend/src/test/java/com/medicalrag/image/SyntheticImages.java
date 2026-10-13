package com.medicalrag.image;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
/** Anonymous geometric teaching fixtures, created at run time; no patient files. */
public final class SyntheticImages {
    public static byte[] create(String format,int width,int height) throws IOException {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) image.setRGB(x,y,x<width/2 ? 0x126d82 : 0xf4b653);
        try (var output=new ByteArrayOutputStream()) { if (!ImageIO.write(image,format,output)) throw new IOException("No fixture encoder"); return output.toByteArray(); }
        finally { image.flush(); }
    }
    public static byte[] png() throws IOException { return create("png",32,24); }
    public static byte[] jpeg() throws IOException { return create("jpg",32,24); }
    public static byte[] jpegWithExif() throws IOException {
        byte[] jpeg=jpeg();
        // Valid empty little-endian TIFF IFD in an EXIF APP1 segment. Preserve verbatim.
        byte[] exif={'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,0,0,0,0,0,0};
        var output=new ByteArrayOutputStream(); output.write(jpeg,0,2); output.write(255);output.write(225);output.write(0);output.write(exif.length+2);output.write(exif);output.write(jpeg,2,jpeg.length-2);return output.toByteArray();
    }
    public static byte[] invalidCompressedPngWithValidStructure() throws IOException {
        byte[] png=png();int offset=8;
        while (offset<png.length) {
            int length=java.nio.ByteBuffer.wrap(png,offset,4).getInt();
            if (new String(png,offset+4,4,java.nio.charset.StandardCharsets.US_ASCII).equals("IDAT")) {
                png[offset+8]=0; // Invalid zlib header; recalculate chunk CRC so A5 structure validation passes.
                var crc=new java.util.zip.CRC32();crc.update(png,offset+4,length+4);java.nio.ByteBuffer.wrap(png,offset+8+length,4).putInt((int)crc.getValue());return png;
            }
            offset+=length+12;
        }
        throw new IOException("Fixture missing IDAT");
    }
    public static void main(String[] args) throws IOException {
        if (args.length!=1) throw new IllegalArgumentException("Owned fixture directory required");
        Path root=Path.of(args[0]); if (!Files.isDirectory(root)) throw new IllegalArgumentException("Create test directory first");
        Files.write(root.resolve("synthetic.png"),png(),StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("synthetic.jpg"),jpeg(),StandardOpenOption.CREATE_NEW);
    }
}
