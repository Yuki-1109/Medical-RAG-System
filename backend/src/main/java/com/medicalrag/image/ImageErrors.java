package com.medicalrag.image;

import java.time.Instant;
import com.medicalrag.knowledgebase.KnowledgeBaseController;
import com.medicalrag.knowledgebase.KnowledgeBaseException;
import com.medicalrag.storage.StorageException;
import com.medicalrag.web.ApiErrorController.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={ImageController.class})
public class ImageErrors {
    private static final Logger LOG=LoggerFactory.getLogger(ImageErrors.class);
    @ExceptionHandler(ImageException.class)
    ResponseEntity<ApiError> document(ImageException error) {
        // Log safe codes only, never causes containing host paths, names or binary contents.
        if (error.getSuppressed().length>0) LOG.error("Image operation failed: code={}, compensationFailures={}",error.code,error.getSuppressed().length);
        return error(error.status,error.code,error.getMessage());
    }
    @ExceptionHandler(KnowledgeBaseException.class)
    ResponseEntity<ApiError> base(KnowledgeBaseException error) { return error(error.status(),error.code(),error.getMessage()); }
    @ExceptionHandler(StorageException.class)
    ResponseEntity<ApiError> storage(StorageException error) {
        int status=switch (error.code()) {
            case EMPTY_FILE,INVALID_NAME,UNSUPPORTED_FORMAT,FORMAT_MISMATCH,DAMAGED_FILE -> 400;
            case TOO_LARGE -> 413;
            case INVALID_REFERENCE,INTEGRITY_MISMATCH,ALREADY_EXISTS -> 409;
            default -> 503;
        };
        LOG.warn("Private file operation failed: code={}",error.code());
        return error(status,"STORAGE_"+error.code(),error.getMessage());
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge() { return error(413,"IMAGE_TOO_LARGE","图片 或上传请求超过本机大小限制，请缩小文件后重试"); }
    @ExceptionHandler({MultipartException.class,MissingServletRequestPartException.class})
    ResponseEntity<ApiError> invalidMultipart() { return error(400,"INVALID_DOCUMENT","请使用文件选择按钮上传一个 图片 文件"); }
    private ResponseEntity<ApiError> error(int status,String code,String message) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,Instant.now()));
    }
}
