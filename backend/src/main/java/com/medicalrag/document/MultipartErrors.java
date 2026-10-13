package com.medicalrag.document;

import java.time.Instant;
import com.medicalrag.web.ApiErrorController.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Multipart resolution can fail before Spring selects a controller. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class MultipartErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge() {
        return ResponseEntity.status(413).header("Cache-Control","no-store")
                .body(new ApiError("PDF_TOO_LARGE","PDF 或上传请求超过本机大小限制，请缩小文件后重试",Instant.now()));
    }
}
