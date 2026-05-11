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
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestType;
import ru.team.novelbot.service.AdminStatsService;
import ru.team.novelbot.service.AccessDeniedException;
import ru.team.novelbot.service.AppException;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.TextTools;
import ru.team.novelbot.service.UsageException;
import ru.team.novelbot.service.UserAuthService;
import ru.team.novelbot.telegram.MiniAppAuthService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

@Component
public class HttpRoutes {
    private final AppProperties properties;
    private final UserAuthService userAuthService;
    private final ChapterService chapterService;
    private final LlmRequestService llmRequestService;
    private final AdminStatsService adminStatsService;
    private final MiniAppAuthService miniAppAuthService;

    public HttpRoutes(
            AppProperties properties,
            UserAuthService userAuthService,
            ChapterService chapterService,
            LlmRequestService llmRequestService,
            AdminStatsService adminStatsService,
            MiniAppAuthService miniAppAuthService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.userAuthService = userAuthService;
        this.chapterService = chapterService;
        this.llmRequestService = llmRequestService;
        this.adminStatsService = adminStatsService;
        this.miniAppAuthService = miniAppAuthService;
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route(GET("/healthcheck"), this::healthcheck)
                .andRoute(GET("/users"), this::users)
                .andRoute(GET("/admin"), this::adminPanel)
                .andRoute(GET("/admin/api/overview"), this::adminOverview)
                .andRoute(GET("/admin/api/users"), this::adminUsers)
                .andRoute(GET("/admin/api/novels"), this::adminNovels)
                .andRoute(GET("/admin/api/llm-requests"), this::adminLlmRequests)
                .andRoute(GET("/"), this::chapterEditor)
                .andRoute(GET("/mini/chapter-editor"), this::chapterEditor)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}"), this::miniGetChapter)
                .andRoute(PUT("/mini/api/chapters/{novelId}/{chapterId}"), this::miniSaveChapter)
                .andRoute(GET("/mini/api/chapters/{novelId}/{chapterId}/history"), this::miniChapterHistory)
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

    private Mono<ServerResponse> users(ServerRequest request) {
        if (!adminTokenValid(request)) {
            return accessDenied();
        }
        List<Map<String, Object>> users = userAuthService.findAll().stream()
                .map(this::toDto)
                .toList();
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(users);
    }

    private Mono<ServerResponse> adminPanel(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(adminHtml());
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

    private Mono<ServerResponse> miniCreateLlmRequest(ServerRequest request) {
        try {
            long chatId = miniChatId(request);
            long novelId = pathLong(request, "novelId");
            long chapterId = pathLong(request, "chapterId");
            return request.bodyToMono(Map.class)
                    .flatMap(body -> createMiniLlmRequest(chatId, novelId, chapterId, body))
                    .onErrorResume(AccessDeniedException.class, ex -> error(HttpStatus.FORBIDDEN, ex.getMessage()))
                    .onErrorResume(AppException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()))
                    .onErrorResume(IllegalArgumentException.class, ex -> error(HttpStatus.BAD_REQUEST, ex.getMessage()));
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (RuntimeException ex) {
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
        } catch (AppException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (RuntimeException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private long miniChatId(ServerRequest request) {
        return miniAppAuthService.requireChatId(request.headers().firstHeader("X-Telegram-Init-Data"));
    }

    private long pathLong(ServerRequest request, String name) {
        return Long.parseLong(request.pathVariable(name));
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

    private Map<String, Object> llmDto(LlmRequest request) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", request.id());
        dto.put("chat_id", request.chatId());
        dto.put("novel_id", request.novelId());
        dto.put("chapter_id", request.chapterId() == null ? "" : request.chapterId());
        dto.put("request_type", request.requestType().name());
        dto.put("status", request.status().name());
        dto.put("result", request.result() == null ? "" : request.result());
        dto.put("error_message", request.errorMessage() == null ? "" : request.errorMessage());
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

    private boolean adminTokenValid(ServerRequest request) {
        return properties.httpAdminToken().equals(request.headers().firstHeader("X-Admin-Token"));
    }

    private Map<String, Object> toDto(AppUser user) {
        return Map.of(
                "chat_id", user.chatId(),
                "username", user.username() == null ? "" : user.username(),
                "role", user.role().displayName(),
                "created_at", user.createdAt().toString()
        );
    }

    private String adminHtml() {
        return """
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Админ-панель бота</title>
                  <style>
                    :root { font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #17202a; background: #f4f7f9; }
                    body { margin: 0; }
                    main { max-width: 1180px; margin: 0 auto; padding: 20px; display: grid; gap: 16px; }
                    h1, h2 { margin: 0; letter-spacing: 0; }
                    h1 { font-size: 28px; }
                    h2 { font-size: 18px; }
                    .toolbar, section { background: #ffffff; border: 1px solid #d9e2e7; border-radius: 8px; padding: 14px; }
                    .toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
                    input, button { font: inherit; border-radius: 8px; border: 1px solid #b9c7d1; padding: 9px 11px; }
                    input { min-width: min(420px, 100%); flex: 1; }
                    button { background: #1f6feb; color: white; border-color: #1f6feb; cursor: pointer; }
                    button.secondary { background: #ffffff; color: #17202a; border-color: #b9c7d1; }
                    .grid { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); }
                    .metric { border: 1px solid #d9e2e7; border-radius: 8px; padding: 12px; background: #fbfcfd; }
                    .metric strong { display: block; font-size: 24px; }
                    .status { color: #607080; }
                    table { width: 100%; border-collapse: collapse; font-size: 14px; }
                    th, td { border-bottom: 1px solid #e4ebf0; padding: 8px; text-align: left; vertical-align: top; }
                    th { color: #405060; font-weight: 600; }
                    .scroll { overflow-x: auto; }
                  </style>
                </head>
                <body>
                <main>
                  <h1>Админ-панель бота</h1>
                  <div class="toolbar">
                    <input id="token" type="password" placeholder="X-Admin-Token">
                    <button id="load">Обновить</button>
                    <span class="status" id="status"></span>
                  </div>
                  <section>
                    <h2>Статистика</h2>
                    <div class="grid" id="overview"></div>
                  </section>
                  <section>
                    <h2>Пользователи</h2>
                    <div class="scroll" id="users"></div>
                  </section>
                  <section>
                    <h2>Романы</h2>
                    <div class="scroll" id="novels"></div>
                  </section>
                  <section>
                    <h2>LLM-запросы</h2>
                    <div class="scroll" id="llm"></div>
                  </section>
                </main>
                <script>
                  const token = document.getElementById('token');
                  const statusEl = document.getElementById('status');
                  const saved = localStorage.getItem('adminToken');
                  if (saved) token.value = saved;

                  function headers() {
                    return {'X-Admin-Token': token.value.trim()};
                  }
                  async function api(path) {
                    const res = await fetch(path, {headers: headers()});
                    if (!res.ok) throw new Error(await res.text());
                    return res.json();
                  }
                  function metric(label, value) {
                    return `<div class="metric"><strong>${value ?? 0}</strong>${label}</div>`;
                  }
                  function renderOverview(data) {
                    document.getElementById('overview').innerHTML = [
                      metric('Пользователи', data.users),
                      metric('Администраторы', data.admins),
                      metric('Романы', data.novels),
                      metric('Главы', data.chapters),
                      metric('Участники', data.authors),
                      metric('Владельцы', data.owners),
                      metric('Соавторы', data.co_authors),
                      metric('Слова', data.words),
                      metric('Символы', data.characters),
                      metric('LLM-запросы', data.llm_requests)
                    ].join('');
                  }
                  function table(rows) {
                    if (!rows.length) return '<p class="status">Нет данных.</p>';
                    const keys = Object.keys(rows[0]);
                    const head = keys.map(k => `<th>${k}</th>`).join('');
                    const body = rows.map(row => `<tr>${keys.map(k => `<td>${row[k] ?? ''}</td>`).join('')}</tr>`).join('');
                    return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
                  }
                  async function load() {
                    localStorage.setItem('adminToken', token.value.trim());
                    statusEl.textContent = 'Загрузка...';
                    const [overview, users, novels, llm] = await Promise.all([
                      api('/admin/api/overview'),
                      api('/admin/api/users'),
                      api('/admin/api/novels'),
                      api('/admin/api/llm-requests')
                    ]);
                    renderOverview(overview);
                    document.getElementById('users').innerHTML = table(users);
                    document.getElementById('novels').innerHTML = table(novels);
                    document.getElementById('llm').innerHTML = table(llm);
                    statusEl.textContent = 'Обновлено';
                  }
                  document.getElementById('load').addEventListener('click', () => load().catch(e => statusEl.textContent = e.message));
                </script>
                </body>
                </html>
                """;
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
                    main { display: grid; gap: 12px; max-width: 1040px; margin: 0 auto; }
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
                    .llm {
                      display: grid;
                      gap: 10px;
                      border: 1px solid var(--tg-theme-hint-color, #b7b7b7);
                      border-radius: 8px;
                      padding: 12px;
                      background: var(--tg-theme-secondary-bg-color, #f5f5f5);
                    }
                    .llm textarea { min-height: 92px; background: var(--tg-theme-bg-color, #ffffff); }
                    .llm-result { min-height: 140px; }
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
                  <section class="llm">
                    <div class="bar">
                      <button id="llmContinue">Продолжить главу</button>
                      <button id="llmAdvice">Совет</button>
                      <button id="llmDraft">Черновик</button>
                      <button id="llmRefresh">Проверить статус</button>
                      <button class="primary" id="llmInsert">Вставить результат</button>
                    </div>
                    <textarea id="llmPrompt" maxlength="1000" placeholder="Вопрос или запрос для совета и черновика"></textarea>
                    <textarea class="llm-result" id="llmResult" readonly placeholder="Результат LLM появится здесь"></textarea>
                    <div class="status" id="llmStatus"></div>
                  </section>
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
                  const llmPrompt = document.getElementById('llmPrompt');
                  const llmResult = document.getElementById('llmResult');
                  const llmStatus = document.getElementById('llmStatus');
                  let updatedAt = null;
                  let dirtyDraft = '';
                  let currentLlmId = null;

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
                  async function createLlm(type) {
                    const prompt = llmPrompt.value.trim();
                    if (type !== 'CONTINUE_CHAPTER' && !prompt) {
                      llmStatus.textContent = 'Введите вопрос или запрос.';
                      return;
                    }
                    llmStatus.textContent = 'LLM-запрос отправляется...';
                    const res = await fetch(`/mini/api/chapters/${novelId}/${chapterId}/llm`, {
                      method: 'POST',
                      headers: headers(),
                      body: JSON.stringify({type, prompt})
                    });
                    if (!res.ok) throw new Error(await res.text());
                    renderLlm(await res.json());
                  }
                  async function refreshLlm() {
                    if (!currentLlmId) {
                      llmStatus.textContent = 'Сначала отправьте LLM-запрос.';
                      return;
                    }
                    const res = await fetch(`/mini/api/llm/${currentLlmId}`, {headers: headers()});
                    if (!res.ok) throw new Error(await res.text());
                    renderLlm(await res.json());
                  }
                  function renderLlm(data) {
                    currentLlmId = data.id;
                    if (data.status === 'DONE') {
                      llmResult.value = data.result || '';
                      llmStatus.textContent = `LLM-запрос #${data.id} готов`;
                      tg?.HapticFeedback?.notificationOccurred('success');
                      return;
                    }
                    if (data.status === 'ERROR') {
                      llmStatus.textContent = data.error_message || `LLM-запрос #${data.id} завершился ошибкой`;
                      return;
                    }
                    llmStatus.textContent = `LLM-запрос #${data.id}: ${data.status}`;
                    setTimeout(() => refreshLlm().catch(e => llmStatus.textContent = e.message), 2500);
                  }
                  function insertLlmResult() {
                    if (!llmResult.value.trim()) {
                      llmStatus.textContent = 'Нет результата для вставки.';
                      return;
                    }
                    const start = text.selectionStart ?? text.value.length;
                    const end = text.selectionEnd ?? text.value.length;
                    const before = text.value.slice(0, start);
                    const after = text.value.slice(end);
                    const addition = (before && !before.endsWith('\\n') ? '\\n\\n' : '') + llmResult.value.trim() + (after && !after.startsWith('\\n') ? '\\n\\n' : '');
                    text.value = before + addition + after;
                    text.focus();
                    text.selectionStart = text.selectionEnd = (before + addition).length;
                    count();
                    llmStatus.textContent = 'Результат вставлен в текст главы. Сохраните изменения.';
                  }
                  text.addEventListener('input', count);
                  title.addEventListener('input', count);
                  saveButton.addEventListener('click', () => save().catch(e => statusEl.textContent = e.message));
                  document.getElementById('download').addEventListener('click', download);
                  document.getElementById('reload').addEventListener('click', () => load().catch(e => statusEl.textContent = e.message));
                  document.getElementById('llmContinue').addEventListener('click', () => createLlm('CONTINUE_CHAPTER').catch(e => llmStatus.textContent = e.message));
                  document.getElementById('llmAdvice').addEventListener('click', () => createLlm('ADVICE').catch(e => llmStatus.textContent = e.message));
                  document.getElementById('llmDraft').addEventListener('click', () => createLlm('DRAFT').catch(e => llmStatus.textContent = e.message));
                  document.getElementById('llmRefresh').addEventListener('click', () => refreshLlm().catch(e => llmStatus.textContent = e.message));
                  document.getElementById('llmInsert').addEventListener('click', insertLlmResult);
                  if (!novelId || !chapterId) {
                    statusEl.textContent = 'Откройте редактор из карточки конкретной главы в боте.';
                    title.disabled = true;
                    text.disabled = true;
                    saveButton.disabled = true;
                    document.querySelectorAll('button, #llmPrompt').forEach(item => item.disabled = true);
                  } else {
                    load().catch(e => statusEl.textContent = e.message);
                  }
                </script>
                </body>
                </html>
                """;
    }
}
