package com.medicalrag.settings;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.medicalrag.settings.ModelTypes.*;

class ModelConnectionTesterTest {
    HttpServer server;
    ModelConnectionTester tester;
    AtomicInteger status=new AtomicInteger(200), count=new AtomicInteger();
    AtomicReference<String> body=new AtomicReference<>(), response=new AtomicReference<>("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
    AtomicReference<String> path=new AtomicReference<>(), authorization=new AtomicReference<>(), cookie=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange -> {
            count.incrementAndGet();path.set(exchange.getRequestURI().getPath());authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if (status.get()==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/stolen");
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();
        var properties=new ModelProperties("unused",true,1000);tester=new ModelConnectionTester(new ObjectMapper(),new ModelAddressPolicy(properties),properties);
    }
    @AfterEach void stop() { tester.close();server.stop(0); }
    Stored config(Type type) { return new Stored(1,type,"OPENAI_COMPATIBLE","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-model","redacted",1,"UNTESTED",null,null,null); }
    @Test void chatMakesActualHttpRequestWithBoundedSyntheticContentAndNoSessionCookie() throws Exception {
        assertThat(tester.test(config(Type.CHAT),"synthetic-only")).isNull();
        assertThat(path.get()).isEqualTo("/v1/chat/completions");assertThat(authorization.get()).isEqualTo("Bearer synthetic-only");assertThat(cookie.get()).isNull();
        var payload=new ObjectMapper().readTree(body.get());assertThat(payload.path("model").asText()).isEqualTo("test-model");assertThat(payload.path("max_tokens").asInt()).isEqualTo(16);assertThat(payload.path("stream").asBoolean()).isFalse();assertThat(payload.path("messages").path(0).path("content").asText()).isEqualTo("Connection test. Reply briefly.");
    }
    @Test void embeddingsUseIndependentEndpointAndValidateVectorDimension() throws Exception {
        response.set("{\"data\":[{\"index\":0,\"embedding\":[0.1,-0.2,0.3]}]}");
        assertThat(tester.test(config(Type.EMBEDDING),"synthetic-only")).isEqualTo(3);assertThat(path.get()).isEqualTo("/v1/embeddings");
        var payload=new ObjectMapper().readTree(body.get());assertThat(payload.path("input").asText()).isEqualTo("Connection test.");assertThat(payload.path("encoding_format").asText()).isEqualTo("float");
    }
    @ParameterizedTest @ValueSource(ints={401,403,404,429,500,503,302,400})
    void actualHttpErrorsAreSanitizedAndRedirectNeverFollowed(int code) {
        status.set(code);response.set("{\"error\":{\"message\":\"synthetic-only Authorization private-response\"}}");
        assertThatThrownBy(() -> tester.test(config(Type.CHAT),"synthetic-only")).isInstanceOf(ModelException.class).hasMessageNotContaining("synthetic-only").hasMessageNotContaining("private-response");
        assertThat(count.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":\" \"}}]}","not-json","{} {}","{\"error\":{\"message\":\"sensitive\"}}"})
    void http200DoesNotMeanSuccess(String value) { response.set(value);assertThatThrownBy(() -> tester.test(config(Type.CHAT),"synthetic-only")).isInstanceOf(ModelException.class); }
    @ParameterizedTest @ValueSource(strings={"{\"data\":[]}","{\"data\":[{\"index\":0,\"embedding\":[]}]}","{\"data\":[{\"index\":0,\"embedding\":[\"0.1\"]}]}","{\"data\":[{\"index\":0,\"embedding\":[1e999]}]}"})
    void invalidVectorsRejected(String value) { response.set(value);assertThatThrownBy(() -> tester.test(config(Type.EMBEDDING),"synthetic-only")).isInstanceOf(ModelException.class); }
    @Test void boundsOversizedResponse() { response.set("x".repeat(ModelConnectionTester.MAX_RESPONSE_BYTES+1));assertThatThrownBy(() -> tester.test(config(Type.CHAT),"synthetic-only")).isInstanceOf(ModelException.class); }
    @Test void overallDeadlineCoversSlowServerAndDnsFailureIsDistinct() throws Exception {
        server.createContext("/slow/chat/completions",exchange -> { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }exchange.close(); });
        Stored slow=new Stored(1,Type.CHAT,"OPENAI_COMPATIBLE","http://127.0.0.1:"+server.getAddress().getPort()+"/slow","test","cipher",1,"UNTESTED",null,null,null);
        assertThatThrownBy(() -> tester.test(slow,"synthetic-only")).isInstanceOfSatisfying(ModelException.class,e -> assertThat(e.code).isEqualTo("MODEL_TIMEOUT"));
        var props=new ModelProperties("unused",true,500);var dns=spy(new ModelAddressPolicy(props));doThrow(new UnknownHostException()).when(dns).resolve(anyString());
            var failed=new ModelConnectionTester(new ObjectMapper(),dns,props);
            try { assertThatThrownBy(() -> failed.test(config(Type.CHAT),"synthetic-only")).isInstanceOfSatisfying(ModelException.class,e -> assertThat(e.code).isEqualTo("MODEL_DNS_ERROR")); } finally { failed.close(); }
    }
}
