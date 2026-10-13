package com.medicalrag.image;

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
public class ImageController {
    private final ImageService images;
    private final AdministratorService administrators;
    private final StorageProperties properties;
    private final ImageProperties dimensions;
    public ImageController(ImageService images,AdministratorService administrators,StorageProperties properties,ImageProperties dimensions) {
        this.images=images; this.administrators=administrators; this.properties=properties; this.dimensions=dimensions;
    }
    private long owner(Principal principal) { return administrators.current(principal.getName()).id(); }
    @GetMapping("/api/images/upload-limits")
    public ResponseEntity<Map<String,Long>> limits() {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("maxImageBytes",properties.maxImageBytes(),"maxWidth",(long)dimensions.maxWidth(),"maxHeight",(long)dimensions.maxHeight(),"maxPixels",dimensions.maxPixels()));
    }
    @PostMapping(value="/api/knowledge-bases/{id}/images",consumes="multipart/form-data")
    public ResponseEntity<ImageInfo> upload(Principal principal,@PathVariable String id,@RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws Exception {
        MultipartHttpServletRequest multipart=WebUtils.getNativeRequest(request,MultipartHttpServletRequest.class);
        if (!request.getParameterMap().isEmpty() || multipart==null || multipart.getMultiFileMap().size()!=1
                || multipart.getFiles("file").size()!=1) throw ImageException.invalid();
        if (file.getSize()>properties.maxImageBytes()) throw new ImageException(413,"IMAGE_TOO_LARGE","图片超过本机配置的大小上限，请缩小文件或调整本机限制");
        try (var input=file.getInputStream()) {
            ImageInfo result=images.upload(KnowledgeBaseService.parseId(id),owner(principal),file.getOriginalFilename(),input);
            return ResponseEntity.status(201).header("Cache-Control","no-store").body(result);
        } catch (IOException error) { throw new ImageException(503,"UPLOAD_IO_FAILURE","读取上传文件失败，请检查本机权限并重试"); }
    }
    @GetMapping("/api/knowledge-bases/{id}/images")
    public ResponseEntity<List<ImageInfo>> list(Principal principal,@PathVariable String id,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ImageException.invalid();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(images.list(KnowledgeBaseService.parseId(id),owner(principal)));
    }
    @GetMapping("/api/images/{id}")
    public ResponseEntity<ImageInfo> detail(Principal principal,@PathVariable String id,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ImageException.invalid();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(images.detail(ImageService.parseId(id),owner(principal)));
    }
    @GetMapping({"/api/images/{id}/content","/api/images/{id}/download"})
    public void content(Principal principal,@PathVariable String id,HttpServletRequest request,HttpServletResponse response) {
        // No async executor: retain the authenticated context and transaction during I/O.
        // The A5 callback starts only after full hash/size verification, before image headers.
        if (!request.getParameterMap().isEmpty()) throw ImageException.invalid();
        long owner=owner(principal), imageId=ImageService.parseId(id);
        ImageInfo info=images.detail(imageId,owner);
        images.content(imageId,owner,channel -> {
            response.setContentType(com.medicalrag.storage.OriginalType.valueOf(info.fileFormat()).mediaType()); response.setContentLengthLong(info.fileSize());
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
    @PatchMapping("/api/images/{id}")
    public ResponseEntity<ImageInfo> update(Principal principal,@PathVariable String id,@RequestBody ImageMetadata input,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ImageException.invalid();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(images.update(ImageService.parseId(id),owner(principal),input));
    }
    @DeleteMapping("/api/images/{id}")
    public ResponseEntity<Void> delete(Principal principal,@PathVariable String id,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ImageException.invalid();
        images.delete(ImageService.parseId(id),owner(principal));
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
