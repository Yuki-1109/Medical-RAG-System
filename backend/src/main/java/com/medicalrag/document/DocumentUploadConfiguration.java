package com.medicalrag.document;

import com.medicalrag.storage.StorageProperties;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods=false)
public class DocumentUploadConfiguration {
    @Bean MultipartConfigElement multipartConfigElement(StorageProperties storage) {
        long maxBytes=Math.max(storage.maxPdfBytes(),storage.maxImageBytes());
        long overhead=1024*1024;
        if (maxBytes>Long.MAX_VALUE-overhead) throw new IllegalArgumentException("PDF 大小配置过大，无法设置上传请求限制");
        var factory=new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(maxBytes));
        factory.setMaxRequestSize(DataSize.ofBytes(maxBytes+overhead));
        factory.setFileSizeThreshold(DataSize.ofBytes(0)); // Servlet temporary disk file, never whole-file heap buffering.
        return factory.createMultipartConfig();
    }
}
