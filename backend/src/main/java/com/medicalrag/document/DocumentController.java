package com.medicalrag.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import com.medicalrag.auth.AdministratorService;
import com.medicalrag.knowledgebase.KnowledgeBaseService;
import com.medicalrag.storage.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

@RestController
public class DocumentController {
    private final DocumentService documents;
    private final AdministratorService administrators;
    private final StorageProperties properties;
    public DocumentController(DocumentService documents,AdministratorService administrators,StorageProperties properties) {
        this.documents=documents; this.administrators=administrators; this.properties=properties;
    }
    private long owner(Principal principal) { return administrators.current(principal.getName()).id(); }
    @GetMapping("/api/documents/upload-limits")
    public ResponseEntity<Map<String,Long>> limits() {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("maxPdfBytes",properties.maxPdfBytes()));
    }
    @PostMapping(value="/api/knowledge-bases/{id}/documents",consumes="multipart/form-data")
    public ResponseEntity<DocumentInfo> upload(Principal principal,@PathVariable String id,@RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws Exception {
        MultipartHttpServletRequest multipart=WebUtils.getNativeRequest(request,MultipartHttpServletRequest.class);
        if (!request.getParameterMap().isEmpty() || multipart==null || multipart.getMultiFileMap().size()!=1
                || multipart.getFiles("file").size()!=1) throw DocumentException.invalid();
        if (file.getSize()>properties.maxPdfBytes()) throw new DocumentException(413,"PDF_TOO_LARGE","PDF 超过本机配置的大小上限，请缩小文件或调整本机限制");
        try (var input=file.getInputStream()) {
            DocumentInfo result=documents.upload(KnowledgeBaseService.parseId(id),owner(principal),file.getOriginalFilename(),input);
            return ResponseEntity.status(201).header("Cache-Control","no-store").body(result);
        } catch (IOException error) { throw new DocumentException(503,"UPLOAD_IO_FAILURE","读取上传文件失败，请检查本机权限并重试"); }
    }
    @GetMapping("/api/knowledge-bases/{id}/documents")
    public ResponseEntity<List<DocumentInfo>> list(Principal principal,@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(documents.list(KnowledgeBaseService.parseId(id),owner(principal)));
    }
    @GetMapping("/api/documents/{id}")
    public ResponseEntity<DocumentInfo> detail(Principal principal,@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(documents.detail(DocumentService.parseId(id),owner(principal)));
    }
    @GetMapping({"/api/documents/{id}/content","/api/documents/{id}/download"})
    public void content(Principal principal,@PathVariable String id,HttpServletRequest request,HttpServletResponse response) {
        // No async executor: retain the authenticated context and transaction during I/O.
        // The A5 callback starts only after full hash/size verification, before PDF headers.
        if (!request.getParameterMap().isEmpty()) throw DocumentException.invalid();
        long owner=owner(principal), documentId=DocumentService.parseId(id);
        DocumentInfo info=documents.detail(documentId,owner);
        documents.content(documentId,owner,channel -> {
            response.setContentType("application/pdf"); response.setContentLengthLong(info.fileSize());
            response.setHeader("Cache-Control","no-store, private"); response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Accept-Ranges","none");
            response.setHeader("Content-Security-Policy","sandbox; default-src 'none'");
            boolean attachment=request.getRequestURI().endsWith("/download");
            response.setHeader("Content-Disposition",(attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                    .filename(info.originalFilename(),StandardCharsets.UTF_8).build().toString());
            var output=response.getOutputStream(); var buffer=ByteBuffer.allocate(8192);
            for (int count;(count=channel.read(buffer))!=-1;) { output.write(buffer.array(),0,count); buffer.clear(); }
            return null;
        });
    }
    @DeleteMapping("/api/documents/{id}")
    public ResponseEntity<Void> delete(Principal principal,@PathVariable String id) {
        documents.delete(DocumentService.parseId(id),owner(principal));
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
