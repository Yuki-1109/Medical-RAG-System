package com.medicalrag.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
record ModelProperties(String keyFile, boolean allowLoopbackHttp, int timeoutMillis) {
    ModelProperties(@Value("${app.models.key-file:secrets/model-master.key}") String keyFile,
                    @Value("${app.models.allow-loopback-http:false}") boolean allowLoopbackHttp,
                    @Value("${app.models.timeout-millis:15000}") int timeoutMillis) {
        this.keyFile=keyFile; this.allowLoopbackHttp=allowLoopbackHttp; this.timeoutMillis=timeoutMillis;
    }
}
