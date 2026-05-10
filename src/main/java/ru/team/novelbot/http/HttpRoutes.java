package ru.team.novelbot.http;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterEditResult;
import ru.team.novelbot.service.AccessDeniedException;
import ru.team.novelbot.service.AppException;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.TextTools;
import ru.team.novelbot.service.UserAuthService;
import ru.team.novelbot.telegram.MiniAppAuthService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

@Component
public class HttpRoutes {
    private final AppProperties properties;
    private final UserAuthService userAuthService;
    private final ChapterService chapterService;
    private final MiniAppAuthService miniAppAuthService;

    public HttpRoutes(
            AppProperties properties,
            UserAuthService userAuthService,
            ChapterService chapterService,
            MiniAppAuthService miniAppAuthService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.userAuthService = userAuthService;
        this.chapterService = chapterService;
        this.miniAppAuthService = miniAppAuthService;
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route(GET("/healthcheck"), this::healthcheck)
                .andRoute(GET("/users"), this::users)
                .andRoute(GET("/"), this::chapterEditor)
                .andRoute(GET("/mini/chapter-editor"), this::chapterEditor)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}"), this::miniGetChapter)
                .andRoute(PUT("/mini/api/chapters/{novelId}/{chapterId}"), this::miniSaveChapter)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}/history"), this::miniChapterHistory);
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

    private Mono<ServerResponse> chapterEditor(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(editorHtml());
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
                    .onErrorResume(AppException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()))
                    .onErrorResume(IllegalArgumentException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
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
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private long miniChatId(ServerRequest request) {
        return miniAppAuthService.requireChatId(request.headers().firstHeader("X-Telegram-Init-Data"));
    }

    private long pathLong(ServerRequest request, String name) {
        return Long.parseLong(request.pathVariable(name));
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

    private String value(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString();
    }

    private Mono<ServerResponse> error(HttpStatus status, String message) {
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", message));
    }

    private Map<String, Object> toDto(AppUser user) {
        return Map.of(
                "chat_id", user.chatId(),
                "username", user.username() == null ? "" : user.username(),
                "role", user.role().displayName(),
                "created_at", user.createdAt().toString()
        );
    }

    private String editorHtml() {
        return """
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <script src="https://telegram.org/js/telegram-web-app.js"></script>
                  <title>Редактор главы</title>
                  <style>
                    :root {
                      color-scheme: light dark;
                      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      background: var(--tg-theme-bg-color, #ffffff);
                      color: var(--tg-theme-text-color, #111111);
                    }
                    body { margin: 0; padding: 16px; }
                    main { display: grid; gap: 12px; max-width: 960px; margin: 0 auto; }
                    input, textarea, button {
                      font: inherit;
                      border: 1px solid var(--tg-theme-hint-color, #b7b7b7);
                      border-radius: 8px;
                      background: var(--tg-theme-secondary-bg-color, #f5f5f5);
                      color: var(--tg-theme-text-color, #111111);
                    }
                    input { padding: 10px 12px; }
                    textarea { min-height: 58vh; padding: 12px; resize: vertical; line-height: 1.5; }
                    .bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
                    button { padding: 10px 12px; cursor: pointer; }
                    button.primary { background: var(--tg-theme-button-color, #2481cc); color: var(--tg-theme-button-text-color, #ffffff); border-color: transparent; }
                    .status { color: var(--tg-theme-hint-color, #707579); }
                    .danger { color: var(--tg-theme-destructive-text-color, #d14); }
                  </style>
                </head>
                <body>
                <main>
                  <input id="title" maxlength="100" placeholder="Название главы">
                  <textarea id="text" placeholder="Текст главы"></textarea>
                  <div class="bar">
                    <button class="primary" id="save">Сохранить</button>
                    <button id="download">Скачать .txt</button>
                    <button id="reload">Перезагрузить</button>
                    <span class="status" id="counter"></span>
                  </div>
                  <div class="status" id="status"></div>
                </main>
                <script>
                  const tg = window.Telegram?.WebApp;
                  tg?.ready();
                  tg?.expand();
                  const params = new URLSearchParams(location.search);
                  const novelId = params.get('novelId');
                  const chapterId = params.get('chapterId');
                  const initData = tg?.initData || '';
                  const title = document.getElementById('title');
                  const text = document.getElementById('text');
                  const counter = document.getElementById('counter');
                  const statusEl = document.getElementById('status');
                  const saveButton = document.getElementById('save');
                  let updatedAt = null;
                  let dirtyDraft = '';

                  function headers() {
                    return {'Content-Type': 'application/json', 'X-Telegram-Init-Data': initData};
                  }
                  function count() {
                    const words = text.value.trim() ? text.value.trim().split(/\\s+/).length : 0;
                    counter.textContent = `${words} слов, ${text.value.length} символов`;
                    dirtyDraft = text.value;
                  }
                  async function load() {
                    statusEl.textContent = 'Загрузка...';
                    const res = await fetch(`/mini/api/chapters/${novelId}/${chapterId}`, {headers: headers()});
                    if (!res.ok) throw new Error(await res.text());
                    const data = await res.json();
                    title.value = data.title;
                    text.value = data.text;
                    updatedAt = data.updated_at;
                    statusEl.textContent = 'Глава загружена';
                    count();
                  }
                  async function save() {
                    statusEl.textContent = 'Сохранение...';
                    const res = await fetch(`/mini/api/chapters/${novelId}/${chapterId}`, {
                      method: 'PUT',
                      headers: headers(),
                      body: JSON.stringify({title: title.value, text: text.value, updated_at: updatedAt})
                    });
                    if (res.status === 409) {
                      statusEl.innerHTML = '<span class="danger">Глава изменилась у другого автора. Скачайте черновик или перезагрузите актуальную версию.</span>';
                      return;
                    }
                    if (!res.ok) throw new Error(await res.text());
                    const data = await res.json();
                    updatedAt = data.updated_at;
                    statusEl.textContent = 'Сохранено';
                    tg?.HapticFeedback?.notificationOccurred('success');
                  }
                  function download() {
                    const blob = new Blob([dirtyDraft || text.value], {type: 'text/plain;charset=utf-8'});
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = `${title.value || 'chapter'}.txt`;
                    a.click();
                    URL.revokeObjectURL(url);
                  }
                  text.addEventListener('input', count);
                  title.addEventListener('input', count);
                  saveButton.addEventListener('click', () => save().catch(e => statusEl.textContent = e.message));
                  document.getElementById('download').addEventListener('click', download);
                  document.getElementById('reload').addEventListener('click', () => load().catch(e => statusEl.textContent = e.message));
                  if (!novelId || !chapterId) {
                    statusEl.textContent = 'Откройте редактор из карточки конкретной главы в боте.';
                    title.disabled = true;
                    text.disabled = true;
                    saveButton.disabled = true;
                  } else {
                    load().catch(e => statusEl.textContent = e.message);
                  }
                </script>
                </body>
                </html>
                """;
    }
}
