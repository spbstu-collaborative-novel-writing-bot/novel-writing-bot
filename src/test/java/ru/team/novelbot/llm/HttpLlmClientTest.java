package ru.team.novelbot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.team.novelbot.config.AppProperties;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLlmClientTest {
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void usesGigachatOauthTokenForChatCompletion() throws Exception {
        AtomicReference<String> oauthAuthorization = new AtomicReference<>();
        AtomicReference<String> oauthBody = new AtomicReference<>();
        AtomicReference<String> chatAuthorization = new AtomicReference<>();
        AtomicReference<String> chatBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth", exchange -> {
            oauthAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            oauthBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"access_token":"token-1","expires_at":%d}
                    """.formatted(Instant.now().plusSeconds(600).toEpochMilli());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/chat", exchange -> {
            chatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"choices":[{"message":{"content":"готово"}}]}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        AppProperties properties = new AppProperties(
                "telegram-token",
                "",
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm(
                        "GIGACHAT",
                        "",
                        "http://127.0.0.1:" + port + "/chat",
                        "",
                        "Basic auth-key",
                        "GIGACHAT API PERS",
                        "http://127.0.0.1:" + port + "/oauth",
                        "",
                        true
                ),
                List.of("Гвоздева Е.")
        );

        String result = new HttpLlmClient(properties, HttpClient.newHttpClient(), new ObjectMapper()).generate("привет");

        assertThat(result).isEqualTo("готово");
        assertThat(oauthAuthorization.get()).isEqualTo("Basic auth-key");
        assertThat(oauthBody.get()).isEqualTo("scope=GIGACHAT+API+PERS");
        assertThat(chatAuthorization.get()).isEqualTo("Bearer token-1");
        assertThat(new ObjectMapper().readTree(chatBody.get()).path("model").asText()).isEqualTo("GigaChat-2");
    }

    @Test
    void refreshesGigachatTokenOnceAfterUnauthorizedChatCompletion() throws Exception {
        AtomicInteger oauthCalls = new AtomicInteger();
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicReference<String> secondChatAuthorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth", exchange -> {
            int call = oauthCalls.incrementAndGet();
            String response = """
                    {"access_token":"token-%d","expires_at":%d}
                    """.formatted(call, Instant.now().plusSeconds(600).toEpochMilli());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/chat", exchange -> {
            int call = chatCalls.incrementAndGet();
            if (call == 1) {
                byte[] bytes = "{\"message\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            secondChatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = """
                    {"choices":[{"message":{"content":"готово"}}]}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        AppProperties properties = new AppProperties(
                "telegram-token",
                "",
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm(
                        "GIGACHAT",
                        "",
                        "http://127.0.0.1:" + port + "/chat",
                        "",
                        "Basic auth-key",
                        "GIGACHAT API PERS",
                        "http://127.0.0.1:" + port + "/oauth",
                        "",
                        true
                ),
                List.of("Гвоздева Е.")
        );

        String result = new HttpLlmClient(properties, HttpClient.newHttpClient(), new ObjectMapper()).generate("привет");

        assertThat(result).isEqualTo("готово");
        assertThat(oauthCalls.get()).isEqualTo(2);
        assertThat(chatCalls.get()).isEqualTo(2);
        assertThat(secondChatAuthorization.get()).isEqualTo("Bearer token-2");
    }

    @Test
    void retriesTransientGigachatChatCompletionFailure() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth", exchange -> {
            String response = """
                    {"access_token":"token-1","expires_at":%d}
                    """.formatted(Instant.now().plusSeconds(600).toEpochMilli());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/chat", exchange -> {
            int call = chatCalls.incrementAndGet();
            if (call == 1) {
                byte[] bytes = "{\"message\":\"temporary failure\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String response = """
                    {"choices":[{"message":{"content":"готово"}}]}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        AppProperties properties = new AppProperties(
                "telegram-token",
                "",
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm(
                        "GIGACHAT",
                        "",
                        "http://127.0.0.1:" + port + "/chat",
                        "",
                        "Basic auth-key",
                        "GIGACHAT_API_PERS",
                        "http://127.0.0.1:" + port + "/oauth",
                        "",
                        true
                ),
                List.of("author")
        );

        String result = new HttpLlmClient(properties, HttpClient.newHttpClient(), new ObjectMapper()).generate("привет");

        assertThat(result).isEqualTo("готово");
        assertThat(chatCalls.get()).isEqualTo(2);
    }
}
