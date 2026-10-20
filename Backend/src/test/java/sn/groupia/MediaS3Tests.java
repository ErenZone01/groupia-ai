package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import sn.groupia.media.domain.model.MediaRendition;
import sn.groupia.media.infrastructure.storage.*;

class MediaS3Tests {
    @Test
    void s3ProtocolContractRunsLocallyWithoutCloudCredentials() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var objects = new ConcurrentHashMap<String, byte[]>();
        var hashes = new ConcurrentHashMap<String, String>();
        var requests = Collections.synchronizedList(new ArrayList<Map<String, String>>());
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath(),
                            method = exchange.getRequestMethod();
                    var h = exchange.getRequestHeaders();
                    requests.add(
                            Map.of(
                                    "method",
                                    method,
                                    "path",
                                    path,
                                    "cache",
                                    Objects.toString(h.getFirst("Cache-Control"), ""),
                                    "type",
                                    Objects.toString(h.getFirst("Content-Type"), ""),
                                    "encoding",
                                    Objects.toString(h.getFirst("Content-Encoding"), ""),
                                    "disposition",
                                    Objects.toString(h.getFirst("Content-Disposition"), "")));
                    if (method.equals("PUT")) {
                        objects.put(path, exchange.getRequestBody().readAllBytes());
                        hashes.put(path, h.getFirst("x-amz-meta-sha256"));
                        exchange.sendResponseHeaders(200, -1);
                    } else if (method.equals("HEAD")) {
                        if (objects.containsKey(path)) {
                            exchange.getResponseHeaders()
                                    .set(
                                            "Content-Length",
                                            String.valueOf(objects.get(path).length));
                            exchange.getResponseHeaders()
                                    .set("x-amz-meta-sha256", hashes.get(path));
                            exchange.sendResponseHeaders(200, -1);
                        } else exchange.sendResponseHeaders(404, -1);
                    } else if (method.equals("DELETE")) {
                        objects.remove(path);
                        exchange.sendResponseHeaders(204, -1);
                    } else exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                });
        server.start();
        try {
            var config =
                    MediaImageTests.properties(
                            Map.of(
                                    "groupia.media.enabled",
                                    true,
                                    "groupia.media.storage-type",
                                    "s3",
                                    "groupia.media.s3.endpoint",
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "groupia.media.s3.region",
                                    "auto",
                                    "groupia.media.s3.bucket",
                                    "media-test",
                                    "groupia.media.s3.access-key",
                                    UUID.randomUUID().toString(),
                                    "groupia.media.s3.secret-key",
                                    UUID.randomUUID().toString(),
                                    "groupia.media.s3.path-style-access",
                                    true,
                                    "groupia.media.public-base-url",
                                    "https://images.example.test"));
            config.validate(false);
            try (var storage = new S3MediaStorage(config)) {
                String key =
                        "organizations/"
                                + UUID.randomUUID()
                                + "/products/"
                                + UUID.randomUUID()
                                + "/"
                                + UUID.randomUUID()
                                + "/v1/display.jpg";
                byte[] bytes = MediaImageTests.image("jpeg", 2, 2, false);
                var image =
                        new MediaRendition(
                                key,
                                "image/jpeg",
                                2,
                                2,
                                bytes.length,
                                FilesystemMediaStorage.sha(bytes));
                assertThat(storage.head(key)).isEmpty();
                storage.put(image, bytes);
                assertThat(storage.head(key).orElseThrow().sha256()).isEqualTo(image.sha256());
                assertThat(storage.head(key).orElseThrow().sizeBytes()).isEqualTo(bytes.length);
                assertThat(storage.resolveUrl(key)).isEqualTo("https://images.example.test/" + key);
                var put =
                        requests.stream()
                                .filter(r -> r.get("method").equals("PUT"))
                                .findFirst()
                                .orElseThrow();
                assertThat(put.get("path")).isEqualTo("/media-test/" + key);
                assertThat(put.get("type")).isEqualTo("image/jpeg");
                assertThat(put.get("disposition")).isEqualTo("inline");
                assertThat(put.get("cache")).contains("immutable", "31536000");
                assertThat(put.get("encoding")).doesNotContain("aws-chunked");
                assertThat(objects.get("/media-test/" + key)).isEqualTo(bytes);
                storage.delete(key);
                storage.delete(key);
                assertThat(storage.head(key)).isEmpty();
            }
        } finally {
            server.stop(0);
        }
    }
}
