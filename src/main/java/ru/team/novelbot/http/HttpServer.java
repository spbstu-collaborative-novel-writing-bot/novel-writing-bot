package ru.team.novelbot.http;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.DisposableServer;
import ru.team.novelbot.config.AppProperties;

@Component
public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final AppProperties properties;
    private final HttpRoutes routes;
    private DisposableServer server;

    public HttpServer(AppProperties properties, HttpRoutes routes) {
        this.properties = properties;
        this.routes = routes;
    }

    public void start() {
        HttpHandler handler = RouterFunctions.toHttpHandler(routes.routes());
        ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(handler);
        server = reactor.netty.http.server.HttpServer.create()
                .host("0.0.0.0")
                .port(properties.httpPort())
                .handle(adapter)
                .bindNow();
        log.info("HTTP server started on port {}.", properties.httpPort());
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.disposeNow();
        }
    }
}
