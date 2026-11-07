package sn.groupia.payment.infrastructure.provider.saspay;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local HTTP only. Fixtures are synthetic and contain no provider credentials. */
public final class SaspayHttpStub implements AutoCloseable {
    public record Request(
            String method, String path, String apiKey, String body, String idempotencyKey) {}

    record Reply(int status, String body, long delay, boolean disconnect) {}

    final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SaspayHttpStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/",
                exchange -> {
                    var bytes = exchange.getRequestBody().readAllBytes();
                    requests.add(
                            new Request(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().toString(),
                                    exchange.getRequestHeaders().getFirst("Authorization"),
                                    new String(bytes, StandardCharsets.UTF_8),
                                    exchange.getRequestHeaders().getFirst("idempotency-key")));
                    var reply = replies.poll();
                    if (reply == null) {
                        reply = new Reply(500, "unexpected request", 0, false);
                    }
                    try {
                        if (reply.delay() > 0) {
                            Thread.sleep(reply.delay());
                        }
                        if (reply.disconnect()) {
                            return;
                        }
                        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(reply.status(), body.length);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
    }

    public void reply(int status, String body) {
        replies.add(new Reply(status, body, 0, false));
    }

    public void delayed(int status, String body, long delay) {
        replies.add(new Reply(status, body, delay, false));
    }

    public void disconnect() {
        replies.add(new Reply(200, "", 0, true));
    }

    public void reset() {
        requests.clear();
        replies.clear();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
