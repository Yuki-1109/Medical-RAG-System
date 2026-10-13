package com.medicalrag.document;

import java.io.IOException;
import java.nio.channels.FileChannel;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

@Component
public class PdfValidator {
    public int pageCount(FileChannel original) {
        try (var reader=new ChannelPdfReader(original);
                var document=new PDFParser(reader,"",null,null,() -> new ScratchFile(MemoryUsageSetting.setupMixed(16L*1024*1024))).parse(false)) {
            // PDFBox's high-level catalog wrapper may repair a wrong /Type. Inspect
            // the actual root before creating that wrapper instead of accepting repair.
            var root=document.getDocument().getTrailer().getCOSDictionary(COSName.ROOT);
            if (root==null || !COSName.CATALOG.equals(root.getCOSName(COSName.TYPE))) throw new IOException("Invalid PDF catalog");
            var pages=root.getCOSDictionary(COSName.PAGES);
            if (pages==null || !COSName.PAGES.equals(pages.getCOSName(COSName.TYPE))) throw new IOException("Invalid PDF pages root");
            int count=document.getNumberOfPages();
            if (count<1 || count>10000) throw new DocumentException(400,"PDF_PAGE_LIMIT","PDF 页数必须为 1～10000 页，请检查文档或拆分后上传");
            int actual=0;
            for (var page : document.getPages()) {
                if (++actual>10000 || !COSName.PAGE.equals(page.getCOSObject().getCOSName(COSName.TYPE))) throw new IOException("Invalid PDF page tree");
                var bounds=page.getMediaBox();
                if (bounds==null || !Float.isFinite(bounds.getWidth()) || !Float.isFinite(bounds.getHeight())
                        || bounds.getWidth()<=0 || bounds.getHeight()<=0) throw new IOException("Invalid PDF page structure");
            }
            if (actual!=count) throw new IOException("PDF page count disagrees with its tree");
            return count;
        } catch (InvalidPasswordException exception) {
            throw new DocumentException(400,"PDF_PASSWORD_REQUIRED","PDF 需要密码才能读取，请先在本机解密并另存可读取的 PDF 后上传");
        } catch (IOException | IllegalArgumentException exception) {
            throw new DocumentException(400,"INVALID_PDF","PDF 无法正常解析或结构损坏，请检查原文件后重新上传");
        }
    }
}
