package com.medicalrag.settings;

import java.security.Principal;
import java.util.List;
import com.medicalrag.auth.AdministratorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.medicalrag.settings.ModelTypes.*;

@RestController
@RequestMapping("/api/settings/models")
class ModelController {
    private final ModelService models;
    private final AdministratorService administrators;
    ModelController(ModelService models, AdministratorService administrators) { this.models=models; this.administrators=administrators; }
    private long owner(Principal principal,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ModelException.invalid();
        return administrators.current(principal.getName()).id();
    }
    @GetMapping ResponseEntity<List<Info>> list(Principal principal,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(models.list(owner(principal,request)));
    }
    @PutMapping ResponseEntity<Info> save(Principal principal,HttpServletRequest request,@RequestBody Input input) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(models.save(owner(principal,request),input));
    }
    @PostMapping("/test") ResponseEntity<Info> test(Principal principal,HttpServletRequest request,@RequestBody TestInput input) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(models.test(owner(principal,request),input));
    }
}
