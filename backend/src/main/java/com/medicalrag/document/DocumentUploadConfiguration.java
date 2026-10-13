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
        long overhead=1024*1024;
        if (storage.maxPdfBytes()>Long.MAX_VALUE-overhead) throw new IllegalArgumentException("PDF 大小配置过大，无法设置上传请求限制");
        var factory=new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(storage.maxPdfBytes()));
        factory.setMaxRequestSize(DataSize.ofBytes(storage.maxPdfBytes()+overhead));
        factory.setFileSizeThreshold(DataSize.ofBytes(0)); // Servlet temporary disk file, never whole-file heap buffering.
        return factory.createMultipartConfig();
    }
}
