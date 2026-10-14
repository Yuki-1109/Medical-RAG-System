package com.medicalrag.settings;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import static com.medicalrag.settings.ModelTypes.*;

@Component
class ModelConnectionTester {
    static final int MAX_RESPONSE_BYTES=262144;
    private final ObjectMapper json;
    private final ModelAddressPolicy addresses;
    private final ModelProperties properties;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots=new Semaphore(2);
    ModelConnectionTester(ObjectMapper json, ModelAddressPolicy addresses, ModelProperties properties) {
        this.json=json; this.addresses=addresses; this.properties=properties;
    }
    @PreDestroy void close() { executor.shutdownNow(); }
    Integer test(Stored config, String key) {
        if (!slots.tryAcquire()) throw new ModelException(429,"MODEL_TEST_BUSY","已有模型连接测试正在执行，请稍后再试");
        HttpPost request;
        try {
            URI base=addresses.base(config.baseUrl());
            request=new HttpPost(base.toString().replaceAll("/+$","")+(config.type()==Type.CHAT?"/chat/completions":"/embeddings"));
        } catch (RuntimeException e) { slots.release(); throw e; }
        Future<Integer> task;
        try { task=executor.submit(() -> { try { return execute(config,key,request); } finally { slots.release(); } }); }
        catch (RuntimeException e) { slots.release(); throw new ModelException(503,"MODEL_NETWORK_ERROR","模型连接服务暂时不可用"); }
        try { return task.get(properties.timeoutMillis(),TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { request.cancel(); task.cancel(true); throw failure("MODEL_TIMEOUT"); }
        catch (InterruptedException e) { request.cancel(); task.cancel(true); Thread.currentThread().interrupt(); throw failure("MODEL_NETWORK_ERROR"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof ModelException error) throw error;
            Throwable cause=e.getCause();
            while (cause!=null) {
                if (cause instanceof UnknownHostException) throw failure("MODEL_DNS_ERROR");
                if (cause instanceof SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException) throw failure("MODEL_TIMEOUT");
                cause=cause.getCause();
            }
            throw failure("MODEL_NETWORK_ERROR");
        }
    }
    private Integer execute(Stored config, String key, HttpPost request) throws Exception {
        var timeout=Timeout.ofMilliseconds(properties.timeoutMillis());
        try (var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(addresses)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build()).build();
             var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout)
                    .setConnectionRequestTimeout(timeout).build()).build()) {
            request.setHeader("Authorization","Bearer "+key); request.setHeader("Accept","application/json");
            Map<String,Object> body=config.type()==Type.CHAT
                    ? Map.of("model",config.modelId(),"messages",List.of(Map.of("role","user","content","Connection test. Reply briefly.")),"max_tokens",16,"stream",false)
                    : Map.of("model",config.modelId(),"input","Connection test.","encoding_format","float");
            request.setEntity(new StringEntity(json.writeValueAsString(body),ContentType.APPLICATION_JSON));
            try (var response=client.executeOpen(null,request,null)) {
                int status=response.getCode();
                if (status==401 || status==403) throw failure("MODEL_AUTH_FAILED");
                if (status==404) throw failure("MODEL_NOT_FOUND");
                if (status==429) throw failure("MODEL_RATE_LIMITED");
                if (status>=500) throw failure("MODEL_SERVER_ERROR");
                if (status>=300 && status<400) throw failure("MODEL_REDIRECT_REJECTED");
                var entity=response.getEntity();
                if (entity==null || entity.getContentLength()>MAX_RESPONSE_BYTES) throw failure("MODEL_INVALID_RESPONSE");
                byte[] bytes;
                try (var stream=entity.getContent()) { bytes=stream.readNBytes(MAX_RESPONSE_BYTES+1); }
                if (bytes.length>MAX_RESPONSE_BYTES) throw failure("MODEL_INVALID_RESPONSE");
                JsonNode result;
                try { result=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes); }
                catch (IOException e) { throw failure("MODEL_INVALID_RESPONSE"); }
                if (status!=200) {
                    String code=result==null?"":result.path("error").path("code").asText();
                    if (Set.of("model_not_found","invalid_model","model_not_available").contains(code)) throw failure("MODEL_NOT_FOUND");
                    throw failure("MODEL_PROTOCOL_ERROR");
                }
                return parse(config.type(),result);
            } finally { request.removeHeaders("Authorization"); }
        }
    }
    static Integer parse(Type type, JsonNode result) {
        if (result==null || !result.isObject() || result.has("error")) throw failure("MODEL_INVALID_RESPONSE");
        if (type==Type.CHAT) {
            JsonNode choices=result.path("choices"); JsonNode text=choices.path(0).path("message").path("content");
            if (!choices.isArray() || !text.isTextual() || text.asText().isBlank()) throw failure("MODEL_INVALID_RESPONSE");
            return null;
        }
        JsonNode data=result.path("data"), vector=data.path(0).path("embedding");
        if (!data.isArray() || data.size()!=1 || !data.path(0).path("index").isIntegralNumber() || data.path(0).path("index").asInt(-1)!=0 || !vector.isArray() || vector.isEmpty()) throw failure("MODEL_INVALID_RESPONSE");
        for (JsonNode number:vector) if (!number.isNumber() || !Double.isFinite(number.asDouble())) throw failure("MODEL_INVALID_RESPONSE");
        return vector.size();
    }
    static ModelException failure(String code) { return new ModelException(502,code,message(code)); }
    static String message(String code) {
        if (code==null) return null;
        return switch (code) {
            case "MODEL_AUTH_FAILED" -> "API Key 无效、已过期或没有访问权限，请检查密钥";
            case "MODEL_NOT_FOUND" -> "Model ID 不存在、无权访问或 Base URL 路径不正确，请核对服务文档";
            case "MODEL_RATE_LIMITED" -> "模型服务请求过于频繁或额度不足（429），请稍后再试并检查额度";
            case "MODEL_SERVER_ERROR" -> "模型服务发生 5xx 错误，请稍后再试";
            case "MODEL_REDIRECT_REJECTED" -> "模型接口返回重定向，已拒绝转发密钥，请填写最终 API Base URL";
            case "MODEL_TIMEOUT" -> "模型请求超时，请检查服务地址和网络后重试";
            case "MODEL_DNS_ERROR" -> "模型服务域名解析失败，请检查 Base URL 和 DNS";
            case "MODEL_NETWORK_ERROR" -> "无法安全连接模型服务，请检查地址、网络和 HTTPS 证书";
            case "MODEL_PROTOCOL_ERROR" -> "模型接口拒绝请求，协议或 Base URL 不兼容，请核对 OpenAI-compatible 接口";
            case "MODEL_INVALID_RESPONSE" -> "模型响应格式无效、内容为空或响应过大，不能判定连接成功";
            case "MODEL_KEY_UNAVAILABLE" -> ModelException.key().getMessage();
            case "MODEL_DECRYPT_FAILED" -> ModelException.decrypt().getMessage();
            case "MODEL_URL_UNSAFE" -> "模型地址指向未经授权的本机、私网或保留地址，已拒绝连接";
            case "MODEL_TEST_BUSY" -> "已有模型连接测试正在执行，请稍后再试";
            default -> "模型测试未完成，请检查配置后重新测试";
        };
    }
}
