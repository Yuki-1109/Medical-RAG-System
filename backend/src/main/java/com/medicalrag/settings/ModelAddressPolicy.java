package com.medicalrag.settings;

import java.net.*;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;
import org.springframework.stereotype.Component;

@Component
class ModelAddressPolicy implements DnsResolver {
    private final ModelProperties properties;
    ModelAddressPolicy(ModelProperties properties) { this.properties=properties; }
    URI base(String value) {
        try {
            if (value==null || value.length()>2048 || !value.equals(value.strip()) || value.contains("%") || value.contains("\\")) throw ModelException.invalid();
            URI uri=URI.create(value);
            String host=uri.getHost();
            if (host==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || uri.getPort()==0 || uri.getPort()>65535 || uri.getPath().contains("..")
                    || !uri.getPath().matches("[/A-Za-z0-9_.~-]*")) throw ModelException.invalid();
            boolean local=host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
            if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && local && properties.allowLoopbackHttp())) {
                throw new ModelException(400,"MODEL_URL_UNSAFE","远程模型地址必须使用 HTTPS；仅显式启用后可使用受信任的 127.0.0.1 或 [::1] 本机 HTTP 服务");
            }
            String lower=host.toLowerCase(Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local") || lower.contains("metadata")) throw unsafe();
            // Reject non-public IP literals during save; DNS is resolved again and checked at each socket connection.
            if (host.matches("[0-9.]+") || host.contains(":")) for (var address : InetAddress.getAllByName(host)) check(address,local);
            return uri;
        } catch (ModelException e) { throw e; } catch (Exception e) { throw ModelException.invalid(); }
    }
    private ModelException unsafe() { return new ModelException(400,"MODEL_URL_UNSAFE","模型地址指向未经授权的本机、私网或保留地址，请使用公共 HTTPS API 地址"); }
    void check(InetAddress address, boolean literalLocal) {
        if (address.isLoopbackAddress() && literalLocal && properties.allowLoopbackHttp()) return;
        byte[] bytes=address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) throw unsafe();
        if (bytes.length==4) {
            int a=Byte.toUnsignedInt(bytes[0]), b=Byte.toUnsignedInt(bytes[1]);
            if (a==0 || a==10 || a==127 || a>=224 || a==169&&b==254 || a==172&&b>=16&&b<=31 || a==192&&b==168
                    || a==100&&b>=64&&b<=127 || a==192&&b==0 || a==192&&b==2 || a==198&&(b==18||b==19||b==51) || a==203&&b==0) throw unsafe();
        } else {
            // Only IPv6 global unicast; reject unique-local, mapped, translation/tunnel and documentation blocks.
            int a=Byte.toUnsignedInt(bytes[0]), b=Byte.toUnsignedInt(bytes[1]);
            if ((a&0xe0)!=0x20 || a==0x20&&b==0x02 || a==0x20&&b==0x01 && (bytes[2]==0 || bytes[2]==0x0d&&bytes[3]==(byte)0xb8)) throw unsafe();
        }
    }
    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses=InetAddress.getAllByName(host);
        boolean local=host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
        for (var address:addresses) check(address,local);
        return addresses; // HttpClient connects to these checked addresses, avoiding a second unchecked DNS lookup.
    }
    @Override public String resolveCanonicalHostname(String host) { return host; }
}
