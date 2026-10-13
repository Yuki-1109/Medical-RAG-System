package com.medicalrag.web;

import java.time.Instant;
import com.medicalrag.auth.AdministratorService.SetupAlreadyCompletedException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SetupAlreadyCompletedException.class)
    ResponseEntity<ApiErrorController.ApiError> setupAlreadyCompleted() {
        return error(409, "SETUP_ALREADY_COMPLETED", "管理员已创建，首次初始化入口已关闭");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorController.ApiError> invalidRequest() {
        // Never serialize rejected values: validation failures may contain plaintext credentials.
        return error(400, "INVALID_REQUEST", "请求格式错误；用户名需为 3～64 位字母、数字、点、下划线或连字符，密码不能为空");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorController.ApiError> invalidPassword() {
        return error(400, "INVALID_PASSWORD", "密码至少需要 12 个字符，UTF-8 编码长度不得超过 72 字节");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiErrorController.ApiError> databaseUnavailable() {
        return error(503, "DATABASE_UNAVAILABLE", "数据库暂时不可用，请检查本机数据库状态");
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    ResponseEntity<ApiErrorController.ApiError> staleAccount() {
        return error(401, "AUTHENTICATION_REQUIRED", "当前管理员不存在，请重新登录");
    }

    private ResponseEntity<ApiErrorController.ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new ApiErrorController.ApiError(code, message, Instant.now()));
    }
}
