package com.medicalrag.settings;

import java.time.Instant;
import com.medicalrag.web.ApiErrorController.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ModelController.class)
class ModelErrors {
    @ExceptionHandler(ModelException.class) ResponseEntity<ApiError> model(ModelException error) {
        return ResponseEntity.status(error.status).header("Cache-Control","no-store").body(new ApiError(error.code,error.getMessage(),Instant.now()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ApiError> invalid() { return model(ModelException.invalid()); }
}
