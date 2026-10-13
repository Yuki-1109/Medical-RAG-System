package com.medicalrag.document;

public class DocumentException extends RuntimeException {
    final int status;
    final String code;
    public DocumentException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static DocumentException missing() { return new DocumentException(404, "DOCUMENT_NOT_FOUND", "文献不存在或无权访问，请返回当前知识库重新选择"); }
    public static DocumentException invalid() { return new DocumentException(400, "INVALID_DOCUMENT", "请仅上传一个 PDF 文件；不接受路径、归属、ID 或其他额外参数"); }
}
