package com.medicalrag.document;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

/** Synthetic English research text / colored pixels only, no private or copyrighted files. */
public final class SyntheticPdfs {
    private static final java.util.Map<Integer,byte[]> TEXT=new java.util.HashMap<>();
    private SyntheticPdfs() { }
    public static synchronized byte[] text(int pages) throws Exception {
        if (TEXT.containsKey(pages)) return TEXT.get(pages).clone();
        try (var document=new PDDocument(); var output=new ByteArrayOutputStream()) {
            for (int i=1;i<=pages;i++) {
                var page=new PDPage(); document.addPage(page);
                try (var content=new PDPageContentStream(document,page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),18);
                    content.newLineAtOffset(50,700); content.showText("Synthetic research fixture - page "+i); content.endText();
                }
            }
            document.save(output,CompressParameters.NO_COMPRESSION); byte[] result=output.toByteArray(); TEXT.put(pages,result); return result.clone();
        }
    }
    public static byte[] scan() throws Exception {
        try (var document=new PDDocument(); var output=new ByteArrayOutputStream()) {
            var page=new PDPage(); document.addPage(page);
            var pixels=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);
            for (int y=0;y<32;y++) for (int x=0;x<32;x++) pixels.setRGB(x,y,((x+y)%2==0 ? 0x336699 : 0x99aabb));
            var image=LosslessFactory.createFromImage(document,pixels);
            try (var content=new PDPageContentStream(document,page)) { content.drawImage(image,40,500,160,160); }
            document.save(output,CompressParameters.NO_COMPRESSION); return output.toByteArray();
        }
    }
    public static byte[] encrypted() throws Exception {
        try (var document=new PDDocument(); var output=new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            var policy=new StandardProtectionPolicy("Synthetic-Owner-Only","Synthetic-Reader-Only",new AccessPermission());
            policy.setEncryptionKeyLength(128); document.protect(policy);
            document.save(output,CompressParameters.NO_COMPRESSION); return output.toByteArray();
        }
    }
}
