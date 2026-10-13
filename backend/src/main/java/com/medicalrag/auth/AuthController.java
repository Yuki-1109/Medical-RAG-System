package com.medicalrag.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AdministratorService service;

    public AuthController(AdministratorService service) {
        this.service = service;
    }

    @GetMapping("/setup-status")
    public SetupStatus setupStatus() {
        return new SetupStatus(service.isInitialized());
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    public Administrator setup(@Valid @RequestBody SetupRequest request) {
        return service.setup(request.username(), request.password());
    }

    @GetMapping("/me")
    public Administrator me(Authentication authentication) {
        return service.current(authentication.getName());
    }

    public record SetupStatus(boolean initialized) { }
    public record CsrfResponse(String headerName, String parameterName, String token) { }

    public record SetupRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
            @NotBlank @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
        @Override
        public String toString() {
            return "SetupRequest[credentials=REDACTED]";
        }
    }
}
