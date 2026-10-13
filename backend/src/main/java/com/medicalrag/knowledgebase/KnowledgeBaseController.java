package com.medicalrag.knowledgebase;

import java.security.Principal;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.medicalrag.auth.AdministratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;
    private final AdministratorService administrators;

    public KnowledgeBaseController(KnowledgeBaseService service, AdministratorService administrators) {
        this.service = service;
        this.administrators = administrators;
    }

    private long owner(Principal principal) { return administrators.current(principal.getName()).id(); }

    @GetMapping
    public ResponseEntity<List<KnowledgeBase>> list(Principal principal) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(owner(principal)));
    }

    @PostMapping
    public ResponseEntity<KnowledgeBase> create(Principal principal, @RequestBody Input input) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store")
                .body(service.create(owner(principal), input.name(), input.description()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBase> detail(Principal principal, @PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.detail(KnowledgeBaseService.parseId(id), owner(principal)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<KnowledgeBase> update(Principal principal, @PathVariable String id, @RequestBody Input input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.update(KnowledgeBaseService.parseId(id), owner(principal), input.name(), input.description()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Principal principal, @PathVariable String id,
            @RequestParam(required=false) Long expectedImageCount, @RequestParam(required=false) Long expectedDocumentCount, @RequestParam(required=false) Long expectedPendingCleanupCount) {
        service.delete(KnowledgeBaseService.parseId(id), owner(principal),expectedDocumentCount,expectedImageCount,expectedPendingCleanupCount);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    public record Input(String name, String description) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Input parse(JsonNode body) {
            if (body == null || !body.isObject() || !body.path("name").isTextual()
                    || (body.hasNonNull("description") && !body.get("description").isTextual())) throw KnowledgeBaseException.invalid();
            body.fieldNames().forEachRemaining(key -> {
                if (!key.equals("name") && !key.equals("description")) throw KnowledgeBaseException.invalid();
            });
            return new Input(body.get("name").asText(), body.path("description").isTextual() ? body.get("description").asText() : "");
        }
    }
}
