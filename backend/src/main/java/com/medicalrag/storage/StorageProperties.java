package com.medicalrag.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage")
public record StorageProperties(String root, long maxPdfBytes, long maxImageBytes) {
    public StorageProperties {
        if (root == null || root.isBlank() || maxPdfBytes <= 0 || maxImageBytes <= 0) {
            throw new StorageException(StorageException.Code.CONFIGURATION, "原件存储目录不能为空，文件大小上限必须为正整数字节数");
        }
    }
}
