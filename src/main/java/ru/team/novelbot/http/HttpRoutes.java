package ru.team.novelbot.http;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterDiffFragment;
import ru.team.novelbot.domain.ChapterDiffPart;
import ru.team.novelbot.domain.ChapterEditResult;
import ru.team.novelbot.domain.ChapterVersion;
import ru.team.novelbot.domain.ChapterVersionDiff;
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.domain.LlmRequestType;
import ru.team.novelbot.service.AccessDeniedException;
import ru.team.novelbot.service.AdminNovelDeletionService;
import ru.team.novelbot.service.AdminStatsService;
import ru.team.novelbot.service.AppException;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.TextTools;
import ru.team.novelbot.service.UsageException;
import ru.team.novelbot.telegram.MiniAppAuthService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

@Component
public class HttpRoutes {
    private static final String FRIENDLY_LLM_ERROR = "Упс... LLM сейчас недоступен. Попробуйте позже.";
    private static final String ADMIN_HTML = "web/admin.html";
    private static final String EDITOR_HTML = "web/mini-chapter-editor.html";

    private final AppProperties properties;
    private final ChapterService chapterService;
    private final LlmRequestService llmRequestService;
    private final AdminStatsService adminStatsService;
    private final AdminNovelDeletionService adminNovelDeletionService;
    private final MiniAppAuthService miniAppAuthService;

    public HttpRoutes(
            AppProperties properties,
            ChapterService chapterService,
            LlmRequestService llmRequestService,
            AdminStatsService adminStatsService,
            AdminNovelDeletionService adminNovelDeletionService,
            MiniAppAuthService miniAppAuthService
    ) {
        this.properties = properties;
        this.chapterService = chapterService;
        this.llmRequestService = llmRequestService;
        this.adminStatsService = adminStatsService;
        this.adminNovelDeletionService = adminNovelDeletionService;
        this.miniAppAuthService = miniAppAuthService;
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route(GET("/healthcheck"), this::healthcheck)
                .andRoute(GET("/admin"), this::adminPanel)
                .andRoute(GET("/admin/api/overview"), this::adminOverview)
                .andRoute(GET("/admin/api/users"), this::adminUsers)
                .andRoute(GET("/admin/api/novels"), this::adminNovels)
                .andRoute(GET("/admin/api/llm-requests"), this::adminLlmRequests)
                .andRoute(POST("/admin/api/novels/{novelId}/delete"), this::adminDeleteNovel)
                .andRoute(GET("/mini/chapter-editor"), this::chapterEditor)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}"), this::miniGetChapter)
                .andRoute(PUT("/mini/api/chapters/{novelId}/{chapterId}"), this::miniSaveChapter)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}/history"), this::miniChapterHistory)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}/versions"), this::miniChapterVersions)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}/versions/{versionNumber}/diff"), this::miniChapterVersionDiff)
                .andRoute(POST("/mini/api/chapters/{novelId}/{chapterId}/llm"), this::miniCreateLlmRequest)
                .andRoute(GET("/mini/api/llm/{requestId}"), this::miniLlmRequest);
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

    private Mono<ServerResponse> adminPanel(ServerRequest request) {
        return html(ADMIN_HTML);
    }

    private Mono<ServerResponse> adminOverview(ServerRequest request) {
        if (!adminTokenValid(request)) {
            return accessDenied();
        }
        return json(adminStatsService.overview());
    }

    private Mono<ServerResponse> adminUsers(ServerRequest request) {
        if (!adminTokenValid(request)) {
            return accessDenied();
        }
        return json(adminStatsService.users());
    }

    private Mono<ServerResponse> adminNovels(ServerRequest request) {
        if (!adminTokenValid(request)) {
            return accessDenied();
        }
        return json(adminStatsService.novels());
    }

    private Mono<ServerResponse> adminLlmRequests(ServerRequest request) {
        if (!adminTokenValid(request)) {
            return accessDenied();
        }
        return json(adminStatsService.llmRequests());
    }

    private Mono<ServerResponse> adminDeleteNovel(ServerRequest request) {
        if (!adminTokenValid(request)) {
            return accessDenied();
        }
        long novelId;
        try {
            novelId = pathLong(request, "novelId");
        } catch (RuntimeException ex) {
            return error(HttpStatus.BAD_REQUEST, "Некорректный id произведения.");
        }
        return request.bodyToMono(Map.class)
                .defaultIfEmpty(Map.of())
                .flatMap(body -> {
                    try {
                        var result = adminNovelDeletionService.deleteNovel(novelId, value(body, "reason"));
                        return json(Map.of(
                                "deleted", true,
                                "id", result.id(),
                                "notified_authors", result.notifiedAuthors()
                        ));
                    } catch (AdminNovelDeletionService.NotificationFailedException ex) {
                        return ServerResponse.status(HttpStatus.CONFLICT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                        "error", ex.getMessage(),
                                        "failed_chat_ids", ex.failedChatIds()
                                ));
                    } catch (UsageException ex) {
                        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
                    } catch (AppException ex) {
                        return error(HttpStatus.NOT_FOUND, ex.getMessage());
                    }
                })
                .onErrorResume(RuntimeException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    private Mono<ServerResponse> chapterEditor(ServerRequest request) {
        return html(EDITOR_HTML);
    }

    private Mono<ServerResponse> miniGetChapter(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            Chapter chapter = chapterService.getChapter(chatId, novelId, chapterId);
            return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(chapterDto(chapter));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private Mono<ServerResponse> miniSaveChapter(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            return request.bodyToMono(Map.class)
                    .flatMap(body -> saveChapter(chatId, novelId, chapterId, body))
                    .onErrorResume(AccessDeniedException.class, ex -> error(HttpStatus.FORBIDDEN, ex.getMessage()))
                    .onErrorResume(UsageException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()))
                    .onErrorResume(AppException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()))
                    .onErrorResume(IllegalArgumentException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private Mono<ServerResponse> saveChapter(long chatId, long novelId, long chapterId, Map<?, ?> body) {
        String title = value(body, "title");
        String text = value(body, "text");
        String expected = value(body, "updated_at");
        if (title.isBlank() || title.length() > 100) {
            return error(HttpStatus.BAD_REQUEST, "Название главы должно быть от 1 до 100 символов.");
        }
        if (text.isBlank() || text.length() > 200000) {
            return error(HttpStatus.BAD_REQUEST, "Текст главы должен быть от 1 до 200000 символов.");
        }
        LocalDateTime expectedUpdatedAt;
        try {
            expectedUpdatedAt = LocalDateTime.parse(expected);
        } catch (RuntimeException ex) {
            return error(HttpStatus.BAD_REQUEST, "Некорректная дата версии главы.");
        }
        ChapterEditResult result = chapterService.updateChapterIfUnchanged(
                chatId,
                novelId,
                chapterId,
                title,
                text,
                expectedUpdatedAt
        );
        if (!result.saved()) {
            return ServerResponse.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "error", "EDIT_CONFLICT",
                            "chapter", chapterDto(result.chapter())
                    ));
        }
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chapterDto(result.chapter()));
    }

    private Mono<ServerResponse> miniChapterHistory(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            var history = chapterService.history(chatId, novelId, chapterId).stream()
                    .map(item -> Map.of(
                            "id", item.id(),
                            "old_title", item.oldTitle(),
                            "old_text_preview", TextTools.compact(item.oldText(), 500),
                            "editor_chat_id", item.editorChatId(),
                            "changed_at", item.changedAt().toString()
                    ))
                    .toList();
            return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(history);
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private Mono<ServerResponse> miniChapterVersions(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            int page = queryInt(request, "page", 0);
            int size = queryInt(request, "size", 20);
            var result = chapterService.versions(chatId, novelId, chapterId, page, size);
            return json(Map.of(
                    "page", result.page(),
                    "size", result.size(),
                    "total", result.total(),
                    "versions", result.versions().stream().map(this::versionDto).toList()
            ));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private Mono<ServerResponse> miniChapterVersionDiff(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            int versionNumber = pathInt(request, "versionNumber");
            return json(diffDto(chapterService.versionDiff(chatId, novelId, chapterId, versionNumber)));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private Mono<ServerResponse> miniCreateLlmRequest(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            return request.bodyToMono(Map.class)
                    .flatMap(body -> createMiniLlmRequest(chatId, novelId, chapterId, body))
                    .onErrorResume(AccessDeniedException.class, ex -> error(HttpStatus.FORBIDDEN, ex.getMessage()))
                    .onErrorResume(UsageException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()))
                    .onErrorResume(AppException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()))
                    .onErrorResume(IllegalArgumentException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private Mono<ServerResponse> createMiniLlmRequest(long chatId, long novelId, long chapterId, Map<?, ?> body) {
        LlmRequestType type = llmType(value(body, "type"));
        String prompt = value(body, "prompt").trim();
        LlmRequest request = switch (type) {
            case CONTINUE_CHAPTER -> llmRequestService.continueChapter(chatId, novelId, chapterId);
            case ADVICE -> {
                validateLlmPrompt(prompt);
                yield llmRequestService.advice(chatId, novelId, prompt);
            }
            case DRAFT -> {
                validateLlmPrompt(prompt);
                yield llmRequestService.draft(chatId, novelId, prompt);
            }
        };
        return json(llmDto(request));
    }

    private Mono<ServerResponse> miniLlmRequest(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long requestId = pathLong(request, "requestId");
            return json(llmDto(llmRequestService.requestStatus(chatId, requestId)));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (UsageException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private long miniChatId(ServerRequest request) {
        return miniAppAuthService.requireChatId(request.headers().firstHeader("X-Telegram-Init-Data"));
    }

    private long pathLong(ServerRequest request, String name) {
        try {
            return Long.parseLong(request.pathVariable(name));
        } catch (NumberFormatException ex) {
            throw new UsageException("Некорректный id.");
        }
    }

    private int pathInt(ServerRequest request, String name) {
        try {
            return Integer.parseInt(request.pathVariable(name));
        } catch (NumberFormatException ex) {
            throw new UsageException("Некорректный номер версии.");
        }
    }

    private int queryInt(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        throw new UsageException("Некорректный параметр " + name + ".");
                    }
                })
                .orElse(defaultValue);
    }

    private LlmRequestType llmType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case "CONTINUE", "CONTINUE_CHAPTER" -> LlmRequestType.CONTINUE_CHAPTER;
            case "ADVICE", "IDEA" -> LlmRequestType.ADVICE;
            case "DRAFT" -> LlmRequestType.DRAFT;
            default -> throw new UsageException("Некорректный тип LLM-запроса.");
        };
    }

    private void validateLlmPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new UsageException("Запрос к LLM не должен быть пустым.");
        }
        if (prompt.length() > 1000) {
            throw new UsageException("Запрос к LLM должен быть не длиннее 1000 символов.");
        }
    }

    private Map<String, Object> chapterDto(Chapter chapter) {
        return Map.of(
                "id", chapter.id(),
                "novel_id", chapter.novelId(),
                "title", chapter.title(),
                "text", chapter.text(),
                "order_number", chapter.orderNumber(),
                "created_at", chapter.createdAt().toString(),
                "updated_at", chapter.updatedAt().toString(),
                "word_count", TextTools.wordCount(chapter.text()),
                "character_count", chapter.text().length()
        );
    }

    private Map<String, Object> versionDto(ChapterVersion version) {
        return Map.of(
                "version_number", version.versionNumber(),
                "chapter_id", version.chapterId(),
                "title", version.title(),
                "changed_at", version.changedAt().toString(),
                "editor_chat_id", version.editorChatId(),
                "editor_name", version.editorName(),
                "is_current", version.current()
        );
    }

    private Map<String, Object> diffDto(ChapterVersionDiff diff) {
        return Map.of(
                "version_number", diff.versionNumber(),
                "previous_version_number", diff.previousVersionNumber(),
                "is_current", diff.current(),
                "is_first_version", diff.firstVersion(),
                "changed_at", diff.changedAt().toString(),
                "editor_name", diff.editorName(),
                "title_changed", diff.titleChanged(),
                "title_fragments", diff.titleFragments().stream().map(this::fragmentDto).toList(),
                "text_fragments", diff.textFragments().stream().map(this::fragmentDto).toList()
        );
    }

    private Map<String, Object> fragmentDto(ChapterDiffFragment fragment) {
        return Map.of("parts", fragment.parts().stream().map(this::partDto).toList());
    }

    private Map<String, Object> partDto(ChapterDiffPart part) {
        return Map.of(
                "type", part.type(),
                "text", part.text()
        );
    }

    private Map<String, Object> llmDto(LlmRequest request) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", request.id());
        dto.put("chat_id", request.chatId());
        dto.put("novel_id", request.novelId());
        dto.put("chapter_id", request.chapterId() == null ? "" : request.chapterId());
        dto.put("request_type", request.requestType().name());
        dto.put("status", request.status().name());
        dto.put("result", request.result() == null ? "" : request.result());
        dto.put("error_message", request.status() == LlmRequestStatus.ERROR ? FRIENDLY_LLM_ERROR : "");
        dto.put("provider", request.provider());
        dto.put("model", request.model());
        dto.put("created_at", request.createdAt().toString());
        dto.put("updated_at", request.updatedAt().toString());
        dto.put("completed_at", request.completedAt() == null ? "" : request.completedAt().toString());
        return dto;
    }

    private String value(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString();
    }

    private Mono<ServerResponse> error(HttpStatus status, String message) {
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", message));
    }

    private Mono<ServerResponse> accessDenied() {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    private Mono<ServerResponse> json(Object body) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    private Mono<ServerResponse> html(String path) {
        try {
            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .bodyValue(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "HTML resource is not available.");
        }
    }

    private boolean adminTokenValid(ServerRequest request) {
        return properties.httpAdminToken().equals(request.headers().firstHeader("X-Admin-Token"));
    }
}
