package com.medicalrag.image;
public class ImageException extends RuntimeException {
    final int status; final String code;
    public ImageException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    public static ImageException missing() { return new ImageException(404,"IMAGE_NOT_FOUND","图片不存在或无权访问，请返回当前知识库重新选择"); }
    public static ImageException invalid() { return new ImageException(400,"INVALID_IMAGE","请仅上传一个名称有效的 JPG、JPEG 或 PNG；不接受路径、归属或额外参数"); }
}
