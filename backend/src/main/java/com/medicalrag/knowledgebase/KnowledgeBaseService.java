package com.medicalrag.knowledgebase;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseMaterials materials;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, KnowledgeBaseMaterials materials) {
        this.repository = repository;
        this.materials = materials;
    }

    public List<KnowledgeBase> list(long owner) {
        materials.requireEmptyStage();
        return repository.list(owner);
    }

    public KnowledgeBase detail(long id, long owner) {
        KnowledgeBase base = repository.find(id, owner, false).orElseThrow(KnowledgeBaseException::missing);
        materials.requireEmptyStage();
        return base;
    }

    @Transactional
    public KnowledgeBase create(long owner, String name, String description) {
        String normalized = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        materials.requireEmptyStage();
        return repository.create(owner, normalized, normalizedDescription);
    }

    @Transactional
    public KnowledgeBase update(long id, long owner, String name, String description) {
        String normalized = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        materials.requireEmptyStage();
        return repository.update(id, owner, normalized, normalizedDescription).orElseThrow(KnowledgeBaseException::missing);
    }

    @Transactional
    public void delete(long id, long owner) {
        repository.find(id, owner, true).orElseThrow(KnowledgeBaseException::missing);
        materials.requireEmptyStage();
        if (!repository.delete(id, owner)) throw KnowledgeBaseException.missing();
    }

    public static long parseId(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new KnowledgeBaseException(400, "INVALID_KNOWLEDGE_BASE_ID", "知识库 ID 格式无效，请从列表选择");
        }
    }

    static String normalizeName(String value) {
        if (value == null) throw KnowledgeBaseException.invalid();
        String name = value.replaceAll("(?U)^\\s+|\\s+$", "");
        if (name.isEmpty() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw KnowledgeBaseException.invalid();
        }
        return name;
    }

    static String normalizeDescription(String value) {
        String description = value == null ? "" : value.strip();
        if (description.length() > 2000 || description.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw KnowledgeBaseException.invalid();
        }
        return description;
    }
}
