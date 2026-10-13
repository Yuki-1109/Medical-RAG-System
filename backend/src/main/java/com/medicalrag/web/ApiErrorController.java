package com.medicalrag.web;

import java.time.Instant;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer code ? code : 500;
        String message = switch (status) {
            case 404 -> "接口不存在，请检查请求地址";
            case 405 -> "请求方法不支持";
            default -> "请求失败，请检查本机服务状态";
        };
        return ResponseEntity.status(status).body(new ApiError("HTTP_" + status, message, Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp) { }
}
