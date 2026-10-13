package com.medicalrag.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Synthetic blank-page PDF and tiny solid-color images; no patient data or licensed originals. */
final class OriginalTestFiles {
    private OriginalTestFiles() { }
    static byte[] pdf() {
        StringBuilder file = new StringBuilder("%PDF-1.7\n% synthetic A5 fixture\n");
        int[] offsets = new int[4];
        String[] objects = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>", "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>"};
        for (int i = 1; i <= 3; i++) { offsets[i] = file.length(); file.append(i).append(" 0 obj\n").append(objects[i - 1]).append("\nendobj\n"); }
        int xref = file.length();
        file.append("xref\n0 4\n0000000000 65535 f \n");
        for (int i = 1; i <= 3; i++) file.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        file.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return file.toString().getBytes(StandardCharsets.US_ASCII);
    }
    static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x237b75); image.setRGB(1, 1, 0xffffff);
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, bytes)) throw new IllegalStateException("JDK test image writer unavailable");
            return bytes.toByteArray();
        }
    }
}
