package com.medicalrag.knowledgebase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBaseMaterials {
    private final JdbcTemplate jdbc;

    public KnowledgeBaseMaterials(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireSupportedStage() {
        // Real documents and images; fail closed when future material cleanup is unavailable.
        Boolean emptyStage = jdbc.queryForObject("""
                SELECT to_regclass('public.documents') IS NOT NULL
                   AND to_regclass('public.medical_images') IS NOT NULL
                   AND to_regclass('public.document_chunks') IS NULL
                   AND to_regclass('public.image_annotations') IS NULL
                """, Boolean.class);
        if (!Boolean.TRUE.equals(emptyStage)) {
            throw new KnowledgeBaseException(409, "MATERIAL_MODULE_UPGRADE_REQUIRED",
                    "检测到资料模块，请使用配套版本后再查看或删除知识库");
        }
    }

    public void requireNoRemainingMaterials(long knowledgeBaseId) {
        requireSupportedStage();
        if (jdbc.queryForObject("SELECT (SELECT count(*) FROM documents WHERE knowledge_base_id = ?) + (SELECT count(*) FROM medical_images WHERE knowledge_base_id = ?)", Long.class, knowledgeBaseId,knowledgeBaseId) != 0) {
            throw new KnowledgeBaseException(409, "KNOWLEDGE_BASE_CLEANUP_PENDING", "知识库资料清理尚未完成，请重试删除；记录与原件凭据已保留");
        }
    }
}
