package com.medicalrag.knowledgebase;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseMaterials materials;
    private final ObjectProvider<KnowledgeBaseDeletion> deletion;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, KnowledgeBaseMaterials materials, ObjectProvider<KnowledgeBaseDeletion> deletion) {
        this.repository = repository;
        this.materials = materials;
        this.deletion = deletion;
    }

    public List<KnowledgeBase> list(long owner) {
        materials.requireSupportedStage();
        return repository.list(owner);
    }

    public KnowledgeBase detail(long id, long owner) {
        KnowledgeBase base = repository.find(id, owner, false).orElseThrow(KnowledgeBaseException::missing);
        materials.requireSupportedStage();
        return base;
    }

    @Transactional
    public KnowledgeBase create(long owner, String name, String description) {
        String normalized = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        materials.requireSupportedStage();
        return repository.create(owner, normalized, normalizedDescription);
    }

    @Transactional
    public KnowledgeBase update(long id, long owner, String name, String description) {
        String normalized = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        materials.requireSupportedStage();
        KnowledgeBase locked = repository.find(id, owner, true).orElseThrow(KnowledgeBaseException::missing);
        if (locked.deletionPending()) throw new KnowledgeBaseException(409, "KNOWLEDGE_BASE_DELETING", "知识库删除未完成，请重试删除，不可编辑或上传新资料");
        return repository.update(id, owner, normalized, normalizedDescription).orElseThrow(KnowledgeBaseException::missing);
    }

    public void delete(long id, long owner) {
        deletion.getObject().delete(id, owner);
    }
    public void delete(long id,long owner,Long expectedDocuments,Long expectedPending) {
        deletion.getObject().delete(id,owner,expectedDocuments,expectedPending);
    }

    public void delete(long id,long owner,Long expectedDocuments,Long expectedImages,Long expectedPending) {
        deletion.getObject().delete(id,owner,expectedDocuments,expectedImages,expectedPending);
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
