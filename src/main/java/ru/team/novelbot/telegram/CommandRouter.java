package ru.team.novelbot.telegram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.AuthorType;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterHistory;
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.domain.NovelAuthor;
import ru.team.novelbot.domain.NovelDetails;
import ru.team.novelbot.domain.NovelStats;
import ru.team.novelbot.domain.NovelSummary;
import ru.team.novelbot.domain.TelegramSession;
import ru.team.novelbot.repository.TelegramSessionRepository;
import ru.team.novelbot.service.AccessDeniedException;
import ru.team.novelbot.service.AppException;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.NovelService;
import ru.team.novelbot.service.TextTools;
import ru.team.novelbot.service.UsageException;
import ru.team.novelbot.service.UserAuthService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class CommandRouter {
    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int NOVELS_PAGE_SIZE = 5;
    private static final int CHAPTERS_PAGE_SIZE = 8;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final UserAuthService userAuthService;
    private final NovelService novelService;
    private final ChapterService chapterService;
    private final LlmRequestService llmRequestService;
    private final TelegramSessionRepository sessionRepository;
    private final TelegramClient telegramClient;

    public CommandRouter(
            AppProperties properties,
            ObjectMapper objectMapper,
            UserAuthService userAuthService,
            NovelService novelService,
            ChapterService chapterService,
            LlmRequestService llmRequestService,
            TelegramSessionRepository sessionRepository,
            TelegramClient telegramClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.userAuthService = userAuthService;
        this.novelService = novelService;
        this.chapterService = chapterService;
        this.llmRequestService = llmRequestService;
        this.sessionRepository = sessionRepository;
        this.telegramClient = telegramClient;
    }

    public TelegramResponse route(TelegramInboundMessage message) {
        userAuthService.registerOrUpdate(message.chatId(), message.username(), message.displayName());
        TelegramResponse.Builder response = TelegramResponse.builder();
        if (message.type() == TelegramUpdateType.CALLBACK) {
            response.add(TelegramAction.answerCallback(message.callbackQueryId(), "", false));
        }
        try {
            TelegramResponse routed = message.type() == TelegramUpdateType.CALLBACK
                    ? routeCallback(message)
                    : routeMessage(message);
            routed.actions().forEach(response::add);
            return response.build();
        } catch (AppException ex) {
            response.add(reply(message, ex.getMessage(), List.of()));
            return response.build();
        } catch (Exception ex) {
            log.error("Ошибка при обработке Telegram update", ex);
            response.add(reply(message, "Не удалось выполнить действие. Попробуйте позже.", List.of()));
            return response.build();
        }
    }

    private TelegramResponse routeMessage(TelegramInboundMessage message) {
        if (message.webAppData() != null && !message.webAppData().isBlank()) {
            return TelegramResponse.of(TelegramAction.send(message.chatId(), "Данные из WebUI получены.", List.of()));
        }
        if (message.textMessage() && "/cancel".equalsIgnoreCase(message.text().trim())) {
            sessionRepository.delete(message.chatId());
            return TelegramResponse.of(TelegramAction.send(message.chatId(), "Текущее действие отменено.", homeKeyboard()));
        }

        Optional<TelegramSession> session = sessionRepository.findByChatId(message.chatId());
        if (session.isPresent() && !isCommand(message.text())) {
            return handleSession(message, session.get());
        }
        if (session.isPresent() && isCommand(message.text())) {
            sessionRepository.delete(message.chatId());
        }

        if (!message.textMessage()) {
            if (message.documentFileId() != null) {
                return TelegramResponse.of(TelegramAction.send(
                        message.chatId(),
                        "Файл можно загрузить после выбора главы и кнопки «Заменить текст».",
                        homeKeyboard()
                ));
            }
            return TelegramResponse.of(TelegramAction.send(
                    message.chatId(),
                    "Пожалуйста, используйте команды и кнопки. Введите /help для краткой справки.",
                    homeKeyboard()
            ));
        }

        ParsedCommand parsed = parse(message.text());
        return switch (parsed.command()) {
            case "/start" -> TelegramResponse.of(TelegramAction.send(message.chatId(), start(), homeKeyboard()));
            case "/help" -> TelegramResponse.of(TelegramAction.send(message.chatId(), help(), homeKeyboard()));
            case "/new" -> startNewNovel(message.chatId());
            case "/novels" -> novels(message.chatId(), 0, null);
            case "/request_status" -> requestStatus(message.chatId(), parsed.args());
            default -> TelegramResponse.of(TelegramAction.send(
                    message.chatId(),
                    "Команда не распознана. Введите /help для списка основных действий.",
                    homeKeyboard()
            ));
        };
    }

    private TelegramResponse routeCallback(TelegramInboundMessage message) {
        String data = message.callbackData() == null ? "" : message.callbackData();
        String[] parts = data.split(":");
        return switch (parts[0]) {
            case "novels" -> novels(message.chatId(), parseInt(parts, 1, 0), message.callbackMessageId());
            case "novel" -> novelCard(message.chatId(), parseLong(parts, 1), message.callbackMessageId());
            case "chapters" -> chapters(message.chatId(), parseLong(parts, 1), parseInt(parts, 2, 0), message.callbackMessageId());
            case "chapter" -> chapterCard(message.chatId(), parseLong(parts, 1), parseLong(parts, 2), message.callbackMessageId());
            case "txt" -> textDocument(message.chatId(), parts);
            case "addch" -> startAddChapter(message.chatId(), parseLong(parts, 1));
            case "edit" -> startChapterEdit(message.chatId(), parts);
            case "hist" -> chapterHistory(message.chatId(), parseLong(parts, 1), parseLong(parts, 2), message.callbackMessageId());
            case "authors" -> authors(message.chatId(), parseLong(parts, 1), message.callbackMessageId());
            case "author" -> authorAction(message.chatId(), parts);
            case "llm" -> llmAction(message.chatId(), parts);
            case "delnovel" -> deleteNovelAction(message.chatId(), parts, message.callbackMessageId());
            case "delchapter" -> deleteChapterAction(message.chatId(), parts, message.callbackMessageId());
            case "new" -> startNewNovel(message.chatId());
            case "noop" -> TelegramResponse.of(TelegramAction.send(message.chatId(), "Действие уже выполнено.", List.of()));
            default -> TelegramResponse.of(TelegramAction.send(message.chatId(), "Кнопка недоступна. Откройте /novels заново.", homeKeyboard()));
        };
    }

    private TelegramResponse handleSession(TelegramInboundMessage message, TelegramSession session) {
        return switch (session.state()) {
            case "CREATE_NOVEL_TITLE" -> sessionNovelTitle(message);
            case "CREATE_NOVEL_DESCRIPTION" -> sessionNovelDescription(message, session);
            case "CREATE_NOVEL_GENRE" -> sessionNovelGenre(message, session);
            case "ADD_CHAPTER_TITLE" -> sessionChapterTitle(message, session);
            case "ADD_CHAPTER_TEXT" -> sessionChapterText(message, session);
            case "REPLACE_CHAPTER_TEXT" -> sessionReplaceChapterText(message, session);
            case "APPEND_CHAPTER_TEXT" -> sessionAppendChapterText(message, session);
            case "RENAME_CHAPTER" -> sessionRenameChapter(message, session);
            case "ADVICE_QUESTION" -> sessionAdvice(message, session);
            case "DRAFT_REQUEST" -> sessionDraft(message, session);
            case "ADD_AUTHOR" -> sessionAddAuthor(message, session);
            case "REMOVE_AUTHOR" -> sessionRemoveAuthor(message, session);
            default -> {
                sessionRepository.delete(message.chatId());
                yield TelegramResponse.of(TelegramAction.send(message.chatId(), "Сценарий сброшен. Начните действие заново.", homeKeyboard()));
            }
        };
    }

    private String start() {
        return """
                Добро пожаловать в Collaborative Novel Writing Bot.

                Основной сценарий:
                /new - создать роман пошагово
                /novels - открыть список романов
                /help - краткая справка
                """;
    }

    private String help() {
        return """
                Команды:
                /new - создать роман без сложного синтаксиса
                /novels - список романов с кнопками
                /cancel - отменить текущее действие

                Внутри карточек романов и глав используйте кнопки: главы, редактор, .txt, история и LLM.
                """;
    }

    private TelegramResponse startNewNovel(long chatId) {
        sessionRepository.save(chatId, "CREATE_NOVEL_TITLE", null, null, "{}");
        return TelegramResponse.of(TelegramAction.send(
                chatId,
                "Введите название романа. Для отмены: /cancel",
                List.of()
        ));
    }

    private TelegramResponse sessionNovelTitle(TelegramInboundMessage message) {
        String title = requireText(message, "Название романа", 100);
        sessionRepository.save(message.chatId(), "CREATE_NOVEL_DESCRIPTION", null, null, json(Map.of("title", title)));
        return TelegramResponse.of(TelegramAction.send(message.chatId(), "Теперь отправьте краткое описание романа.", List.of()));
    }

    private TelegramResponse sessionNovelDescription(TelegramInboundMessage message, TelegramSession session) {
        String description = requireText(message, "Описание романа", 1000);
        Map<String, String> payload = payload(session);
        sessionRepository.save(
                message.chatId(),
                "CREATE_NOVEL_GENRE",
                null,
                null,
                json(Map.of("title", payload.get("title"), "description", description))
        );
        return TelegramResponse.of(TelegramAction.send(message.chatId(), "И последний шаг: отправьте жанр.", List.of()));
    }

    private TelegramResponse sessionNovelGenre(TelegramInboundMessage message, TelegramSession session) {
        String genre = requireText(message, "Жанр", 50);
        Map<String, String> payload = payload(session);
        var novel = novelService.createNovel(message.chatId(), payload.get("title"), payload.get("description"), genre);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.builder()
                .add(TelegramAction.send(message.chatId(), "Роман создан.", List.of()))
                .add(cardAction(message.chatId(), null, novelCardText(novelService.getDetails(message.chatId(), novel.id())), novelKeyboard(novel.id())))
                .build();
    }

    private TelegramResponse novels(long chatId, int page, Integer messageId) {
        List<NovelSummary> novels = novelService.listAccessible(chatId);
        if (novels.isEmpty()) {
            return TelegramResponse.of(cardAction(chatId, messageId, "У вас пока нет доступных романов. Создайте первый через /new.", homeKeyboard()));
        }
        int pageCount = pageCount(novels.size(), NOVELS_PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, pageCount - 1));
        int from = safePage * NOVELS_PAGE_SIZE;
        int to = Math.min(novels.size(), from + NOVELS_PAGE_SIZE);
        StringBuilder text = new StringBuilder("Ваши романы\n\n");
        for (NovelSummary novel : novels.subList(from, to)) {
            text.append("• ")
                    .append(novel.title())
                    .append(" (").append(novel.genre()).append(")")
                    .append("\n  Глав: ").append(novel.chapterCount())
                    .append(", роль: ").append(novel.authorType().displayName())
                    .append("\n  Обновлено: ").append(DATE_FORMAT.format(novel.updatedAt()))
                    .append("\n\n");
        }
        text.append("Страница ").append(safePage + 1).append(" из ").append(pageCount).append(".");
        return TelegramResponse.of(cardAction(chatId, messageId, text.toString(), novelsKeyboard(novels.subList(from, to), safePage, pageCount)));
    }

    private TelegramResponse novelCard(long chatId, long novelId, Integer messageId) {
        NovelDetails details = novelService.getDetails(chatId, novelId);
        return TelegramResponse.of(cardAction(chatId, messageId, novelCardText(details), novelKeyboard(novelId)));
    }

    private String novelCardText(NovelDetails details) {
        String authors = details.authors().stream()
                .map(author -> MessageFormatter.username(author.username()) + " - " + author.authorType().displayName())
                .collect(Collectors.joining("\n"));
        NovelStats stats = details.stats();
        return """
                %s

                Жанр: %s
                Описание: %s
                Создатель: %s
                Создано: %s
                Обновлено: %s

                Глав: %d
                Примерный объём: %d слов, %d символов

                Авторы:
                %s
                """.formatted(
                details.novel().title(),
                details.novel().genre(),
                details.novel().description(),
                MessageFormatter.username(details.owner().username()),
                DATE_FORMAT.format(details.novel().createdAt()),
                DATE_FORMAT.format(details.novel().updatedAt()),
                stats.chapterCount(),
                stats.wordCount(),
                stats.characterCount(),
                authors
        );
    }

    private TelegramResponse chapters(long chatId, long novelId, int page, Integer messageId) {
        List<Chapter> chapters = chapterService.listChapters(chatId, novelId);
        if (chapters.isEmpty()) {
            return TelegramResponse.of(cardAction(
                    chatId,
                    messageId,
                    "В романе пока нет глав.",
                    List.of(
                            List.of(TelegramButton.callback("Добавить главу", "addch:" + novelId)),
                            List.of(TelegramButton.callback("Назад к роману", "novel:" + novelId))
                    )
            ));
        }
        int pageCount = pageCount(chapters.size(), CHAPTERS_PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, pageCount - 1));
        int from = safePage * CHAPTERS_PAGE_SIZE;
        int to = Math.min(chapters.size(), from + CHAPTERS_PAGE_SIZE);
        StringBuilder text = new StringBuilder("Главы романа\n\n");
        for (Chapter chapter : chapters.subList(from, to)) {
            text.append(chapter.orderNumber()).append(". ")
                    .append(chapter.title())
                    .append("\n  Обновлено: ").append(DATE_FORMAT.format(chapter.updatedAt()))
                    .append(", объём: ").append(TextTools.wordCount(chapter.text())).append(" слов")
                    .append("\n\n");
        }
        text.append("Страница ").append(safePage + 1).append(" из ").append(pageCount).append(".");
        return TelegramResponse.of(cardAction(chatId, messageId, text.toString(), chaptersKeyboard(novelId, chapters.subList(from, to), safePage, pageCount)));
    }

    private TelegramResponse chapterCard(long chatId, long novelId, long chapterId, Integer messageId) {
        Chapter chapter = chapterService.getChapter(chatId, novelId, chapterId);
        return TelegramResponse.of(cardAction(chatId, messageId, chapterCardText(chapter), chapterKeyboard(novelId, chapter)));
    }

    private String chapterCardText(Chapter chapter) {
        return """
                Глава %d. %s

                Обновлено: %s
                Объём: %d слов, %d символов

                Фрагмент:
                %s
                """.formatted(
                chapter.orderNumber(),
                chapter.title(),
                DATE_FORMAT.format(chapter.updatedAt()),
                TextTools.wordCount(chapter.text()),
                chapter.text().length(),
                MessageFormatter.excerpt(chapter.text(), 900)
        );
    }

    private TelegramResponse textDocument(long chatId, String[] parts) {
        if (parts.length < 2) {
            throw new UsageException("Некорректная кнопка.");
        }
        if ("chapter".equals(parts[1])) {
            long novelId = parseLong(parts, 2);
            long chapterId = parseLong(parts, 3);
            Chapter chapter = chapterService.getChapter(chatId, novelId, chapterId);
            return TelegramResponse.of(TelegramAction.document(chatId, safeFilename(chapter.title()) + ".txt", chapter.text()));
        }
        if ("novel".equals(parts[1])) {
            long novelId = parseLong(parts, 2);
            return TelegramResponse.of(TelegramAction.document(chatId, "novel-" + novelId + ".txt", chapterService.fullText(chatId, novelId)));
        }
        throw new UsageException("Некорректная кнопка.");
    }

    private TelegramResponse deleteNovelAction(long chatId, String[] parts, Integer messageId) {
        long novelId = parseLong(parts, 1);
        String mode = parts.length > 2 ? parts[2] : "";
        if ("confirm".equals(mode)) {
            novelService.deleteNovel(chatId, novelId);
            return TelegramResponse.of(cardAction(chatId, messageId, "Роман удалён.", homeKeyboard()));
        }
        requireNovelOwner(chatId, novelId);
        NovelDetails details = novelService.getDetails(chatId, novelId);
        return TelegramResponse.of(cardAction(
                chatId,
                messageId,
                "Удалить роман «" + details.novel().title() + "»?",
                List.of(
                        List.of(TelegramButton.callback("Удалить", "delnovel:" + novelId + ":confirm")),
                        List.of(TelegramButton.callback("Назад к роману", "novel:" + novelId))
                )
        ));
    }

    private TelegramResponse deleteChapterAction(long chatId, String[] parts, Integer messageId) {
        long novelId = parseLong(parts, 1);
        long chapterId = parseLong(parts, 2);
        String mode = parts.length > 3 ? parts[3] : "";
        if ("confirm".equals(mode)) {
            chapterService.deleteChapter(chatId, novelId, chapterId);
            return TelegramResponse.of(cardAction(
                    chatId,
                    messageId,
                    "Глава удалена.",
                    List.of(List.of(TelegramButton.callback("К главам", "chapters:" + novelId + ":0")))
            ));
        }
        Chapter chapter = chapterService.getChapter(chatId, novelId, chapterId);
        return TelegramResponse.of(cardAction(
                chatId,
                messageId,
                "Удалить главу " + chapter.orderNumber() + ". " + chapter.title() + "?",
                List.of(
                        List.of(TelegramButton.callback("Удалить", "delchapter:" + novelId + ":" + chapterId + ":confirm")),
                        List.of(TelegramButton.callback("Назад к главе", "chapter:" + novelId + ":" + chapterId))
                )
        ));
    }

    private TelegramResponse startAddChapter(long chatId, long novelId) {
        novelService.requireNovel(chatId, novelId);
        sessionRepository.save(chatId, "ADD_CHAPTER_TITLE", novelId, null, "{}");
        return TelegramResponse.of(TelegramAction.send(chatId, "Введите название новой главы.", List.of()));
    }

    private TelegramResponse sessionChapterTitle(TelegramInboundMessage message, TelegramSession session) {
        String title = requireText(message, "Название главы", 100);
        sessionRepository.save(message.chatId(), "ADD_CHAPTER_TEXT", session.novelId(), null, json(Map.of("title", title)));
        return TelegramResponse.of(TelegramAction.send(message.chatId(), "Теперь отправьте текст главы. Можно обычным сообщением или .txt файлом.", List.of()));
    }

    private TelegramResponse sessionChapterText(TelegramInboundMessage message, TelegramSession session) {
        String text = textOrDocument(message);
        String title = payload(session).get("title");
        Chapter chapter = chapterService.addChapter(message.chatId(), session.novelId(), title, text);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.builder()
                .add(TelegramAction.send(message.chatId(), "Глава добавлена.", List.of()))
                .add(TelegramAction.send(message.chatId(), chapterCardText(chapter), chapterKeyboard(session.novelId(), chapter)))
                .build();
    }

    private TelegramResponse startChapterEdit(long chatId, String[] parts) {
        String mode = parts.length > 1 ? parts[1] : "";
        long novelId = parseLong(parts, 2);
        long chapterId = parseLong(parts, 3);
        Chapter chapter = chapterService.getChapter(chatId, novelId, chapterId);
        return switch (mode) {
            case "replace" -> {
                sessionRepository.save(chatId, "REPLACE_CHAPTER_TEXT", novelId, chapterId, "{}");
                yield TelegramResponse.of(TelegramAction.send(chatId, "Отправьте новый полный текст главы сообщением или .txt файлом.", List.of()));
            }
            case "append" -> {
                sessionRepository.save(chatId, "APPEND_CHAPTER_TEXT", novelId, chapterId, "{}");
                yield TelegramResponse.of(TelegramAction.send(chatId, "Отправьте фрагмент, который нужно добавить в конец главы.", List.of()));
            }
            case "rename" -> {
                sessionRepository.save(chatId, "RENAME_CHAPTER", novelId, chapterId, "{}");
                yield TelegramResponse.of(TelegramAction.send(chatId, "Введите новое название главы «" + chapter.title() + "».", List.of()));
            }
            default -> throw new UsageException("Некорректная кнопка редактирования.");
        };
    }

    private TelegramResponse sessionReplaceChapterText(TelegramInboundMessage message, TelegramSession session) {
        String text = textOrDocument(message);
        Chapter old = chapterService.getChapter(message.chatId(), session.novelId(), session.chapterId());
        Chapter chapter = chapterService.updateChapter(message.chatId(), session.novelId(), session.chapterId(), old.title(), text);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.of(TelegramAction.send(message.chatId(), "Текст главы обновлён.", chapterKeyboard(session.novelId(), chapter)));
    }

    private TelegramResponse sessionAppendChapterText(TelegramInboundMessage message, TelegramSession session) {
        String text = textOrDocument(message);
        Chapter chapter = chapterService.appendToChapter(message.chatId(), session.novelId(), session.chapterId(), text);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.of(TelegramAction.send(message.chatId(), "Фрагмент добавлен в конец главы.", chapterKeyboard(session.novelId(), chapter)));
    }

    private TelegramResponse sessionRenameChapter(TelegramInboundMessage message, TelegramSession session) {
        String title = requireText(message, "Название главы", 100);
        Chapter old = chapterService.getChapter(message.chatId(), session.novelId(), session.chapterId());
        Chapter chapter = chapterService.updateChapter(message.chatId(), session.novelId(), session.chapterId(), title, old.text());
        sessionRepository.delete(message.chatId());
        return TelegramResponse.of(TelegramAction.send(message.chatId(), "Глава переименована.", chapterKeyboard(session.novelId(), chapter)));
    }

    private TelegramResponse chapterHistory(long chatId, long novelId, long chapterId, Integer messageId) {
        List<ChapterHistory> history = chapterService.history(chatId, novelId, chapterId);
        String text = history.isEmpty()
                ? "История изменений главы пока пуста."
                : history.stream()
                .map(item -> """
                        Версия #%d, изменено: %s, редактор: %d
                        Старое название: %s
                        Фрагмент: %s
                        """.formatted(
                        item.id(),
                        DATE_FORMAT.format(item.changedAt()),
                        item.editorChatId(),
                        item.oldTitle(),
                        MessageFormatter.excerpt(item.oldText(), 180)
                ))
                .collect(Collectors.joining("\n"));
        return TelegramResponse.of(cardAction(chatId, messageId, text, List.of(List.of(TelegramButton.callback("Назад к главе", "chapter:" + novelId + ":" + chapterId)))));
    }

    private TelegramResponse authors(long chatId, long novelId, Integer messageId) {
        List<NovelAuthor> authors = novelService.listAuthors(chatId, novelId);
        boolean owner = authors.stream()
                .anyMatch(author -> author.chatId() == chatId && author.authorType() == AuthorType.OWNER);
        String text = authors.stream()
                .map(author -> "%d %s - %s".formatted(
                        author.chatId(),
                        MessageFormatter.username(author.username()),
                        author.authorType().displayName()
                ))
                .collect(Collectors.joining("\n"));
        var rows = new java.util.ArrayList<List<TelegramButton>>();
        if (owner) {
            rows.add(List.of(
                    TelegramButton.callback("Добавить владельца", "author:add:" + novelId + ":OWNER"),
                    TelegramButton.callback("Добавить соавтора", "author:add:" + novelId + ":CO_AUTHOR")
            ));
            rows.add(List.of(TelegramButton.callback("Удалить участника", "author:remove:" + novelId)));
        }
        rows.add(List.of(TelegramButton.callback("Назад к роману", "novel:" + novelId)));
        return TelegramResponse.of(cardAction(chatId, messageId, text.isBlank() ? "У романа пока нет авторов." : text, List.copyOf(rows)));
    }

    private TelegramResponse authorAction(long chatId, String[] parts) {
        String mode = parts.length > 1 ? parts[1] : "";
        long novelId = parseLong(parts, 2);
        if ("add".equals(mode)) {
            AuthorType authorType = authorType(parts.length > 3 ? parts[3] : "");
            requireNovelOwner(chatId, novelId);
            sessionRepository.save(chatId, "ADD_AUTHOR", novelId, null, json(Map.of("role", authorType.name())));
            String roleName = authorType == AuthorType.OWNER ? "владельца" : "соавтора";
            return TelegramResponse.of(TelegramAction.send(chatId, "Отправьте chat_id " + roleName + ".", List.of()));
        }
        if ("remove".equals(mode)) {
            requireNovelOwner(chatId, novelId);
            sessionRepository.save(chatId, "REMOVE_AUTHOR", novelId, null, "{}");
            return TelegramResponse.of(TelegramAction.send(chatId, "Отправьте chat_id участника, которого нужно удалить.", List.of()));
        }
        throw new UsageException("Некорректная кнопка авторов.");
    }

    private TelegramResponse sessionAddAuthor(TelegramInboundMessage message, TelegramSession session) {
        long invitedChatId = parseLong(requireText(message, "chat_id пользователя", 30), "chat_id указан неверно.");
        AuthorType authorType = authorType(payload(session).get("role"));
        novelService.addAuthor(message.chatId(), session.novelId(), invitedChatId, authorType);
        sessionRepository.delete(message.chatId());
        String roleName = authorType == AuthorType.OWNER ? "Владелец добавлен." : "Соавтор добавлен.";
        return TelegramResponse.builder()
                .add(TelegramAction.send(message.chatId(), roleName, List.of()))
                .add(cardAction(message.chatId(), null, authorsText(message.chatId(), session.novelId()), authorsKeyboard(message.chatId(), session.novelId())))
                .build();
    }

    private TelegramResponse sessionRemoveAuthor(TelegramInboundMessage message, TelegramSession session) {
        long targetChatId = parseLong(requireText(message, "chat_id пользователя", 30), "chat_id указан неверно.");
        novelService.removeAuthor(message.chatId(), session.novelId(), targetChatId);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.builder()
                .add(TelegramAction.send(message.chatId(), "Участник удалён.", List.of()))
                .add(cardAction(message.chatId(), null, authorsText(message.chatId(), session.novelId()), authorsKeyboard(message.chatId(), session.novelId())))
                .build();
    }

    private String authorsText(long chatId, long novelId) {
        String text = novelService.listAuthors(chatId, novelId).stream()
                .map(author -> "%d %s - %s".formatted(
                        author.chatId(),
                        MessageFormatter.username(author.username()),
                        author.authorType().displayName()
                ))
                .collect(Collectors.joining("\n"));
        return text.isBlank() ? "У романа пока нет авторов." : text;
    }

    private List<List<TelegramButton>> authorsKeyboard(long chatId, long novelId) {
        boolean owner = novelService.listAuthors(chatId, novelId).stream()
                .anyMatch(author -> author.chatId() == chatId && author.authorType() == AuthorType.OWNER);
        var rows = new java.util.ArrayList<List<TelegramButton>>();
        if (owner) {
            rows.add(List.of(
                    TelegramButton.callback("Добавить владельца", "author:add:" + novelId + ":OWNER"),
                    TelegramButton.callback("Добавить соавтора", "author:add:" + novelId + ":CO_AUTHOR")
            ));
            rows.add(List.of(TelegramButton.callback("Удалить участника", "author:remove:" + novelId)));
        }
        rows.add(List.of(TelegramButton.callback("Назад к роману", "novel:" + novelId)));
        return List.copyOf(rows);
    }

    private TelegramResponse llmAction(long chatId, String[] parts) {
        String mode = parts.length > 1 ? parts[1] : "";
        if ("continue".equals(mode)) {
            long novelId = parseLong(parts, 2);
            long chapterId = parseLong(parts, 3);
            LlmRequest request = llmRequestService.continueChapter(chatId, novelId, chapterId);
            return TelegramResponse.of(TelegramAction.send(chatId, accepted(request), List.of()));
        }
        if ("advice".equals(mode)) {
            long novelId = parseLong(parts, 2);
            sessionRepository.save(chatId, "ADVICE_QUESTION", novelId, null, "{}");
            return TelegramResponse.of(TelegramAction.send(chatId, "Отправьте вопрос к LLM по этому роману.", List.of()));
        }
        if ("draft".equals(mode)) {
            long novelId = parseLong(parts, 2);
            sessionRepository.save(chatId, "DRAFT_REQUEST", novelId, null, "{}");
            return TelegramResponse.of(TelegramAction.send(chatId, "Опишите, какой черновик главы нужно подготовить.", List.of()));
        }
        if ("add".equals(mode)) {
            long requestId = parseLong(parts, 2);
            LlmRequest request = llmRequestService.requestStatus(chatId, requestId);
            if (request.status() != LlmRequestStatus.DONE || request.result() == null || request.chapterId() == null) {
                throw new AppException("Этот LLM-результат пока нельзя добавить в главу.");
            }
            Chapter chapter = chapterService.appendToChapter(chatId, request.novelId(), request.chapterId(), request.result());
            return TelegramResponse.of(TelegramAction.send(chatId, "LLM-результат добавлен в конец главы.", chapterKeyboard(request.novelId(), chapter)));
        }
        throw new UsageException("Некорректная LLM-кнопка.");
    }

    private TelegramResponse sessionAdvice(TelegramInboundMessage message, TelegramSession session) {
        String question = requireText(message, "Вопрос к LLM", 1000);
        LlmRequest request = llmRequestService.advice(message.chatId(), session.novelId(), question);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.of(TelegramAction.send(message.chatId(), accepted(request), List.of()));
    }

    private TelegramResponse sessionDraft(TelegramInboundMessage message, TelegramSession session) {
        String requestText = requireText(message, "Запрос к LLM", 1000);
        LlmRequest request = llmRequestService.draft(message.chatId(), session.novelId(), requestText);
        sessionRepository.delete(message.chatId());
        return TelegramResponse.of(TelegramAction.send(message.chatId(), accepted(request), List.of()));
    }

    private TelegramResponse requestStatus(long chatId, String args) {
        long requestId = singleLong(args, "Пример: /request_status <request_id>");
        LlmRequest request = llmRequestService.requestStatus(chatId, requestId);
        if (request.status() == LlmRequestStatus.DONE) {
            TelegramResponse.Builder builder = TelegramResponse.builder()
                    .add(TelegramAction.send(chatId, "Статус запроса #" + request.id() + ": DONE\n\nРезультат:\n" + request.result(), llmResultKeyboard(request)));
            if (request.result() != null && request.result().length() > 3000) {
                builder.add(TelegramAction.document(chatId, "llm-request-" + request.id() + ".txt", request.result()));
            }
            return builder.build();
        }
        if (request.status() == LlmRequestStatus.ERROR) {
            return TelegramResponse.of(TelegramAction.send(chatId, "Статус запроса #" + request.id() + ": ERROR\nНе удалось получить ответ от языковой модели. Попробуйте позже.", List.of()));
        }
        return TelegramResponse.of(TelegramAction.send(chatId, "Статус запроса #" + request.id() + ": " + request.status(), List.of()));
    }

    private TelegramAction cardAction(long chatId, Integer messageId, String text, List<List<TelegramButton>> keyboard) {
        if (messageId == null) {
            return TelegramAction.send(chatId, text, keyboard);
        }
        return TelegramAction.edit(chatId, messageId, text, keyboard);
    }

    private TelegramAction reply(TelegramInboundMessage message, String text, List<List<TelegramButton>> keyboard) {
        if (message.type() == TelegramUpdateType.CALLBACK && message.callbackMessageId() != null) {
            return TelegramAction.edit(message.chatId(), message.callbackMessageId(), text, keyboard);
        }
        return TelegramAction.send(message.chatId(), text, keyboard);
    }

    private List<List<TelegramButton>> homeKeyboard() {
        return List.of(
                List.of(TelegramButton.callback("Мои романы", "novels:0")),
                List.of(TelegramButton.callback("Создать роман", "new"))
        );
    }

    private List<List<TelegramButton>> novelsKeyboard(List<NovelSummary> novels, int page, int pageCount) {
        var rows = new java.util.ArrayList<List<TelegramButton>>();
        for (NovelSummary novel : novels) {
            rows.add(List.of(TelegramButton.callback(novel.title(), "novel:" + novel.id())));
        }
        var nav = new java.util.ArrayList<TelegramButton>();
        if (page > 0) {
            nav.add(TelegramButton.callback("Назад", "novels:" + (page - 1)));
        }
        if (page + 1 < pageCount) {
            nav.add(TelegramButton.callback("Вперёд", "novels:" + (page + 1)));
        }
        if (!nav.isEmpty()) {
            rows.add(List.copyOf(nav));
        }
        return List.copyOf(rows);
    }

    private List<List<TelegramButton>> novelKeyboard(long novelId) {
        return List.of(
                List.of(TelegramButton.callback("Главы", "chapters:" + novelId + ":0"), TelegramButton.callback("Добавить главу", "addch:" + novelId)),
                List.of(TelegramButton.callback("Полный текст .txt", "txt:novel:" + novelId), TelegramButton.callback("Авторы", "authors:" + novelId)),
                List.of(TelegramButton.callback("Совет LLM", "llm:advice:" + novelId), TelegramButton.callback("Черновик LLM", "llm:draft:" + novelId)),
                List.of(TelegramButton.callback("Удалить роман", "delnovel:" + novelId)),
                List.of(TelegramButton.callback("К списку романов", "novels:0"))
        );
    }

    private List<List<TelegramButton>> chaptersKeyboard(long novelId, List<Chapter> chapters, int page, int pageCount) {
        var rows = new java.util.ArrayList<List<TelegramButton>>();
        for (Chapter chapter : chapters) {
            rows.add(List.of(TelegramButton.callback(chapter.orderNumber() + ". " + chapter.title(), "chapter:" + novelId + ":" + chapter.id())));
        }
        var nav = new java.util.ArrayList<TelegramButton>();
        if (page > 0) {
            nav.add(TelegramButton.callback("Назад", "chapters:" + novelId + ":" + (page - 1)));
        }
        if (page + 1 < pageCount) {
            nav.add(TelegramButton.callback("Вперёд", "chapters:" + novelId + ":" + (page + 1)));
        }
        if (!nav.isEmpty()) {
            rows.add(List.copyOf(nav));
        }
        rows.add(List.of(TelegramButton.callback("Добавить главу", "addch:" + novelId), TelegramButton.callback("К роману", "novel:" + novelId)));
        return List.copyOf(rows);
    }

    private List<List<TelegramButton>> chapterKeyboard(long novelId, Chapter chapter) {
        var rows = new java.util.ArrayList<List<TelegramButton>>();
        String webAppUrl = editorUrl(novelId, chapter.id());
        if (webAppUrl != null) {
            rows.add(List.of(TelegramButton.webApp("Открыть редактор", webAppUrl)));
        }
        rows.add(List.of(TelegramButton.callback("Скачать .txt", "txt:chapter:" + novelId + ":" + chapter.id()), TelegramButton.callback("Заменить текст", "edit:replace:" + novelId + ":" + chapter.id())));
        rows.add(List.of(TelegramButton.callback("Добавить в конец", "edit:append:" + novelId + ":" + chapter.id()), TelegramButton.callback("Переименовать", "edit:rename:" + novelId + ":" + chapter.id())));
        TelegramButton historyButton = webAppUrl == null
                ? TelegramButton.callback("История", "hist:" + novelId + ":" + chapter.id())
                : TelegramButton.webApp("История", editorUrl(novelId, chapter.id(), "history"));
        rows.add(List.of(historyButton, TelegramButton.callback("Удалить главу", "delchapter:" + novelId + ":" + chapter.id())));
        rows.add(List.of(TelegramButton.callback("LLM продолжить", "llm:continue:" + novelId + ":" + chapter.id())));
        rows.add(List.of(TelegramButton.callback("К главам", "chapters:" + novelId + ":0"), TelegramButton.callback("К роману", "novel:" + novelId)));
        return List.copyOf(rows);
    }

    private List<List<TelegramButton>> llmResultKeyboard(LlmRequest request) {
        if (request.chapterId() == null || request.status() != LlmRequestStatus.DONE) {
            return List.of();
        }
        return List.of(List.of(TelegramButton.callback("Добавить в конец главы", "llm:add:" + request.id())));
    }

    private String editorUrl(long novelId, long chapterId) {
        return editorUrl(novelId, chapterId, null);
    }

    private String editorUrl(long novelId, long chapterId, String view) {
        String base = properties.telegramWebAppUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String separator = base.contains("?") ? "&" : "?";
        String url = base + separator + "novelId=" + novelId + "&chapterId=" + chapterId;
        return view == null || view.isBlank()
                ? url
                : url + "&view=" + URLEncoder.encode(view, StandardCharsets.UTF_8);
    }

    private String accepted(LlmRequest request) {
        return "Запрос принят в обработку. Номер запроса: " + request.id() + ". Я пришлю сообщение, когда LLM ответит или вернёт ошибку.";
    }

    private String textOrDocument(TelegramInboundMessage message) {
        if (message.documentFileId() != null) {
            String filename = message.documentFileName() == null ? "" : message.documentFileName().toLowerCase();
            if (!filename.endsWith(".txt")) {
                throw new UsageException("Поддерживаются только .txt файлы.");
            }
            String text = telegramClient.downloadFileText(message.documentFileId());
            validateText(text, "Текст главы", 200000);
            return text;
        }
        return requireText(message, "Текст главы", 200000);
    }

    private String requireText(TelegramInboundMessage message, String fieldName, int maxLength) {
        if (!message.textMessage()) {
            throw new UsageException(fieldName + " нужно отправить текстом.");
        }
        validateText(message.text(), fieldName, maxLength);
        return message.text().trim();
    }

    private void validateText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new UsageException(fieldName + " не должно быть пустым.");
        }
        if (value.length() > maxLength) {
            throw new UsageException(fieldName + " не должно быть длиннее " + maxLength + " символов.");
        }
    }

    private void requireNovelOwner(long chatId, long novelId) {
        boolean owner = novelService.listAuthors(chatId, novelId).stream()
                .anyMatch(author -> author.chatId() == chatId && author.authorType() == AuthorType.OWNER);
        if (!owner) {
            throw new AccessDeniedException("Действие доступно только владельцу произведения.");
        }
    }

    private AuthorType authorType(String value) {
        try {
            return AuthorType.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new UsageException("Некорректная роль участника.");
        }
    }

    private ParsedCommand parse(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return new ParsedCommand("", "");
        }
        int firstSpace = normalized.indexOf(' ');
        if (firstSpace < 0) {
            return new ParsedCommand(normalized, "");
        }
        return new ParsedCommand(normalized.substring(0, firstSpace), normalized.substring(firstSpace + 1).trim());
    }

    private boolean isCommand(String text) {
        return text != null && text.trim().startsWith("/");
    }

    private String[] splitWhitespace(String args, int expected, String usage) {
        String[] parts = args.trim().split("\\s+", expected);
        if (parts.length < expected || Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new UsageException(usage);
        }
        return parts;
    }

    private long singleLong(String args, String usage) {
        String[] parts = splitWhitespace(args, 1, usage);
        return parseLong(parts[0], "Идентификатор указан неверно.");
    }

    private long parseLong(String value, String error) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new UsageException(error);
        }
    }

    private long parseLong(String[] parts, int index) {
        if (parts.length <= index) {
            throw new UsageException("Некорректная кнопка.");
        }
        return parseLong(parts[index], "Некорректная кнопка.");
    }

    private int parseInt(String[] parts, int index, int defaultValue) {
        if (parts.length <= index) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int pageCount(int size, int pageSize) {
        return Math.max(1, (int) Math.ceil(size / (double) pageSize));
    }

    private Map<String, String> payload(TelegramSession session) {
        try {
            return objectMapper.readValue(session.payload(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new AppException("Не удалось прочитать состояние сценария.", ex);
        }
    }

    private String json(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new AppException("Не удалось сохранить состояние сценария.", ex);
        }
    }

    private String safeFilename(String value) {
        String normalized = value == null ? "chapter" : value.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        if (normalized.isBlank()) {
            return "chapter";
        }
        return URLEncoder.encode(normalized, StandardCharsets.UTF_8).replace("+", "_");
    }

    private record ParsedCommand(String command, String args) {
    }
}
