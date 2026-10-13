package com.medicalrag.knowledgebase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBaseMaterials {
    private final JdbcTemplate jdbc;

    public KnowledgeBaseMaterials(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireEmptyStage() {
        // A4 has no material tables or upload endpoints: every library is genuinely empty.
        // Fail closed if a later schema is present. A6/A7 must replace this with real
        // per-library counts and an explicit nonempty-delete policy, never keep returning 0.
        Boolean emptyStage = jdbc.queryForObject("""
                SELECT to_regclass('public.documents') IS NULL
                   AND to_regclass('public.medical_images') IS NULL
                """, Boolean.class);
        if (!Boolean.TRUE.equals(emptyStage)) {
            throw new KnowledgeBaseException(409, "MATERIAL_MODULE_UPGRADE_REQUIRED",
                    "检测到资料模块，请使用配套版本后再查看或删除知识库");
        }
    }
}
