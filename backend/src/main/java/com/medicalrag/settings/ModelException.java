package com.medicalrag.settings;

final class ModelException extends RuntimeException {
    final int status;
    final String code;
    ModelException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static ModelException invalid() { return new ModelException(400,"INVALID_MODEL_CONFIG","模型配置格式无效，请检查类型、协议、URL、Model ID 和密钥长度"); }
    static ModelException key() { return new ModelException(503,"MODEL_KEY_UNAVAILABLE","模型主密钥缺失、损坏或权限不安全，请恢复原主密钥及私有权限；不会自动替换已有密钥"); }
    static ModelException decrypt() { return new ModelException(503,"MODEL_DECRYPT_FAILED","模型密钥认证解密失败，请恢复匹配的主密钥和配置备份"); }
}
