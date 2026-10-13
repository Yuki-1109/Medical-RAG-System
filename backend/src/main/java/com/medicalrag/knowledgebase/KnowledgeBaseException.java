package com.medicalrag.knowledgebase;

public class KnowledgeBaseException extends RuntimeException {
    final int status;
    final String code;

    public KnowledgeBaseException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static KnowledgeBaseException missing() {
        // Same result for nonexistent and foreign resources; do not reveal their existence.
        return new KnowledgeBaseException(404, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问，请返回列表重新选择");
    }

    public static KnowledgeBaseException invalid() {
        return new KnowledgeBaseException(400, "INVALID_KNOWLEDGE_BASE", "名称需为 1～100 个字符且不能全为空白；简介最多 2000 个字符，不接受控制字符或额外字段");
    }
}
