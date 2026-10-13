package com.medicalrag.web;

import java.io.IOException;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiResponses {
    private final ObjectMapper json;

    public ApiResponses(ObjectMapper json) {
        this.json = json;
    }

    public void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), new ApiErrorController.ApiError(code, message, Instant.now()));
    }
}
