import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.*;

/** Replays only two public HTTPS metadata responses fetched from Mojang for offline tests. */
public final class OfflineGeyserBootstrap {
    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        Map<String, byte[]> responses = Map.of(
            "https://client.discovery.minecraft-services.net/api/v1.0/discovery/MinecraftPE/builds/1.0.0.0", Files.readAllBytes(cache.resolve("discovery.json")),
            "https://authorization.franchise.minecraft-services.net/.well-known/openid-configuration", Files.readAllBytes(cache.resolve("openid.json")));
        ResponseCache.setDefault(new ResponseCache() {
            public CacheResponse get(URI uri, String method, Map<String,List<String>> headers) {
                byte[] bytes = responses.get(uri.toString());
                if (!method.equals("GET") || bytes == null) return null;
                return new SecureCacheResponse() {
                    public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                    public Map<String,List<String>> getHeaders() {
                        Map<String,List<String>> h = new HashMap<>();
                        h.put(null,List.of("HTTP/1.1 200 OK"));
                        h.put("Content-Type",List.of("application/json"));
                        h.put("Content-Length",List.of(String.valueOf(bytes.length)));
                        return h;
                    }
                    public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
                    public List<Certificate> getLocalCertificateChain() { return List.of(); }
                    public List<Certificate> getServerCertificateChain() { return List.of(); }
                    public Principal getPeerPrincipal() { return null; }
                    public Principal getLocalPrincipal() { return null; }
                };
            }
            public CacheRequest put(URI uri, URLConnection connection) { return null; }
        });
        org.geysermc.geyser.platform.standalone.GeyserStandaloneBootstrap.main(new String[]{"--nogui"});
    }
}
