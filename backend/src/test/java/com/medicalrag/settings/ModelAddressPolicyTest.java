package com.medicalrag.settings;

import java.net.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ModelAddressPolicyTest {
    final ModelAddressPolicy policy=new ModelAddressPolicy(new ModelProperties("unused",false,500));
    @ParameterizedTest @ValueSource(strings={"file:///secret","http://example.com/v1","https://user:secret@example.com/v1","https://example.com/v1?token=x","https://example.com/#x","https://127.0.0.1/v1","https://10.1.2.3/v1","https://169.254.169.254/v1","https://100.64.0.1/v1","https://[::1]/v1","https://[fc00::1]/v1","https://localhost/v1","https://example.com/%2e%2e","https://example.com:0/v1","https://example.com/../api"})
    void refusesDangerousUrls(String url) { assertThatThrownBy(() -> policy.base(url)).isInstanceOf(ModelException.class); }
    @Test void acceptsPublicHttpsAndOnlyExplicitLiteralLoopbackOption() {
        assertThat(policy.base("https://api.example.com/v1").getHost()).isEqualTo("api.example.com");
        var local=new ModelAddressPolicy(new ModelProperties("unused",true,500));
        assertThat(local.base("http://127.0.0.1:8088/v1").getHost()).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> local.base("http://localhost:8088/v1")).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> local.base("http://192.168.1.2/v1")).isInstanceOf(ModelException.class);
    }
    @Test void dnsCheckedAddressesRejectPrivateAndAllowPublic() throws Exception {
        assertThatThrownBy(() -> policy.check(InetAddress.getByAddress(new byte[]{10,1,2,3}),false)).isInstanceOf(ModelException.class);
        assertThatCode(() -> policy.check(InetAddress.getByAddress(new byte[]{8,8,8,8}),false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.resolve("127.0.0.1")).isInstanceOf(ModelException.class);
    }
}
