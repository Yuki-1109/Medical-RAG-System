package com.medicalrag.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
    @Bean LocalOriginalStore localOriginalStore(StorageProperties properties) {
        return new LocalOriginalStore(properties, StorageRootResolver.resolve(properties.root(), Path.of("")), new StorageIo());
    }
}
