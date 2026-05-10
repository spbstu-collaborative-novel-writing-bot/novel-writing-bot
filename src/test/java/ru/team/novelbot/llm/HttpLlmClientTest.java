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
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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
        AtomicReference<String> chatAuthorization = new AtomicReference<>();
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
            chatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
                Set.of(100L),
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm(
                        "GIGACHAT",
                        "",
                        "http://127.0.0.1:" + port + "/chat",
                        "GigaChat",
                        "auth-key",
                        "GIGACHAT_API_PERS",
                        "http://127.0.0.1:" + port + "/oauth"
                ),
                List.of("Гвоздева Е.")
        );

        String result = new HttpLlmClient(properties, HttpClient.newHttpClient(), new ObjectMapper()).generate("привет");

        assertThat(result).isEqualTo("готово");
        assertThat(chatAuthorization.get()).isEqualTo("Bearer token-1");
    }
}
