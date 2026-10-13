package com.medicalrag.knowledgebase;

import java.time.Instant;
import com.medicalrag.web.ApiErrorController.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Scoped to this module: retain A2's existing credential-validation responses.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = KnowledgeBaseController.class)
public class KnowledgeBaseErrors {
    @ExceptionHandler(KnowledgeBaseException.class)
    ResponseEntity<ApiError> knowledgeBase(KnowledgeBaseException exception) {
        return error(exception.status, exception.code, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidBody() {
        return error(400, "INVALID_KNOWLEDGE_BASE", "请求格式错误，请只提交文字类型的名称和简介，不接受 ID、归属或其他字段");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> relatedData() {
        return error(409, "KNOWLEDGE_BASE_CONFLICT", "知识库关联数据已变化，操作未完成，请刷新后使用配套资料管理功能");
    }

    private ResponseEntity<ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ApiError(code, message, Instant.now()));
    }
}
