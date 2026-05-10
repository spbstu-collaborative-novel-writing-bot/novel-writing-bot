package ru.team.novelbot.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.service.UserAuthService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@Component
public class HttpRoutes {
    private final AppProperties properties;
    private final UserAuthService userAuthService;

    public HttpRoutes(AppProperties properties, UserAuthService userAuthService) {
        this.properties = properties;
        this.userAuthService = userAuthService;
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route(GET("/healthcheck"), this::healthcheck)
                .andRoute(GET("/users"), this::users);
    }

    private Mono<ServerResponse> healthcheck(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "status", "UP",
                        "project", "Collaborative Novel Writing Bot",
                        "authors", properties.projectAuthors(),
                        "time", LocalDateTime.now().toString()
                ));
    }

    private Mono<ServerResponse> users(ServerRequest request) {
        String token = request.headers().firstHeader("X-Admin-Token");
        if (!properties.httpAdminToken().equals(token)) {
            return ServerResponse.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "ACCESS_DENIED"));
        }
        List<Map<String, Object>> users = userAuthService.findAll().stream()
                .map(this::toDto)
                .toList();
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(users);
    }

    private Map<String, Object> toDto(AppUser user) {
        return Map.of(
                "chat_id", user.chatId(),
                "username", user.username() == null ? "" : user.username(),
                "role", user.role().displayName(),
                "created_at", user.createdAt().toString()
        );
    }
}
