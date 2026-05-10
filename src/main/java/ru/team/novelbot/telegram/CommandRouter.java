package ru.team.novelbot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterHistory;
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.domain.NovelAuthor;
import ru.team.novelbot.domain.NovelDetails;
import ru.team.novelbot.domain.NovelSummary;
import ru.team.novelbot.service.AccessDeniedException;
import ru.team.novelbot.service.AppException;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.NovelService;
import ru.team.novelbot.service.UsageException;
import ru.team.novelbot.service.UserAuthService;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CommandRouter {
    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final UserAuthService userAuthService;
    private final NovelService novelService;
    private final ChapterService chapterService;
    private final LlmRequestService llmRequestService;

    public CommandRouter(
            UserAuthService userAuthService,
            NovelService novelService,
            ChapterService chapterService,
            LlmRequestService llmRequestService
    ) {
        this.userAuthService = userAuthService;
        this.novelService = novelService;
        this.chapterService = chapterService;
        this.llmRequestService = llmRequestService;
    }

    public String route(TelegramInboundMessage message) {
        userAuthService.registerOrUpdate(message.chatId(), message.username(), message.displayName());
        if (!message.textMessage()) {
            return "Пожалуйста, используйте текстовые команды. Введите /help для списка команд.";
        }

        ParsedCommand parsed = parse(message.text());
        try {
            return MessageFormatter.telegramSafe(switch (parsed.command()) {
                case "/start" -> start();
                case "/help" -> help();
                case "/new_novel" -> newNovel(message.chatId(), parsed.args());
                case "/my_novels" -> myNovels(message.chatId());
                case "/novel" -> novel(message.chatId(), parsed.args());
                case "/delete_novel" -> deleteNovel(message.chatId(), parsed.args());
                case "/invite_author" -> inviteAuthor(message.chatId(), parsed.args());
                case "/remove_author" -> removeAuthor(message.chatId(), parsed.args());
                case "/authors" -> authors(message.chatId(), parsed.args());
                case "/add_chapter" -> addChapter(message.chatId(), parsed.args());
                case "/chapters" -> chapters(message.chatId(), parsed.args());
                case "/chapter" -> chapter(message.chatId(), parsed.args());
                case "/full_text" -> fullText(message.chatId(), parsed.args());
                case "/update_chapter" -> updateChapter(message.chatId(), parsed.args());
                case "/delete_chapter" -> deleteChapter(message.chatId(), parsed.args());
                case "/chapter_history" -> chapterHistory(message.chatId(), parsed.args());
                case "/continue_chapter" -> continueChapter(message.chatId(), parsed.args());
                case "/advice" -> advice(message.chatId(), parsed.args());
                case "/draft" -> draft(message.chatId(), parsed.args());
                case "/request_status" -> requestStatus(message.chatId(), parsed.args());
                default -> "Команда не распознана. Введите /help для списка команд.";
            });
        } catch (UsageException | AccessDeniedException ex) {
            return ex.getMessage();
        } catch (AppException ex) {
            return ex.getMessage();
        } catch (Exception ex) {
            log.error("Ошибка при обработке команды {}", parsed.command(), ex);
            return "Не удалось выполнить действие. Попробуйте позже.";
        }
    }

    private String start() {
        return """
                Добро пожаловать в Collaborative Novel Writing Bot.

                Основные команды:
                /new_novel Название | Описание | жанр
                /my_novels
                /add_chapter <novel_id> | Название главы | Текст главы
                /continue_chapter <novel_id> <chapter_id>
                /request_status <request_id>

                Полный список команд: /help
                """;
    }

    private String help() {
        return """
                Команды бота:
                /start - регистрация и приветствие
                /help - справка
                /new_novel <название> | <описание> | <жанр>
                /my_novels
                /novel <novel_id>
                /delete_novel <novel_id>
                /invite_author <novel_id> <chat_id>
                /remove_author <novel_id> <chat_id>
                /authors <novel_id>
                /add_chapter <novel_id> | <название главы> | <текст главы>
                /chapters <novel_id>
                /chapter <novel_id> <chapter_id>
                /full_text <novel_id>
                /update_chapter <novel_id> <chapter_id> | <новое название> | <новый текст>
                /delete_chapter <novel_id> <chapter_id>
                /chapter_history <novel_id> <chapter_id>
                /continue_chapter <novel_id> <chapter_id>
                /advice <novel_id> <вопрос>
                /draft <novel_id> <запрос>
                /request_status <request_id>
                """;
    }

    private String newNovel(long chatId, String args) {
        String[] parts = pipeArgs(args, 3, "Пример: /new_novel Звезды над городом | История о будущем мегаполисе | фантастика");
        validateText(parts[0], "Название произведения", 100);
        validateText(parts[1], "Описание произведения", 1000);
        validateText(parts[2], "Жанр", 50);
        var novel = novelService.createNovel(chatId, parts[0], parts[1], parts[2]);
        return "Произведение создано.\nID: " + novel.id() + "\nНазвание: " + novel.title();
    }

    private String myNovels(long chatId) {
        List<NovelSummary> novels = novelService.listAccessible(chatId);
        if (novels.isEmpty()) {
            return "У вас пока нет доступных произведений.";
        }
        return novels.stream()
                .map(novel -> "%d. %s (%s), роль: %s".formatted(
                        novel.id(),
                        novel.title(),
                        novel.genre(),
                        novel.authorType().displayName()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String novel(long chatId, String args) {
        long novelId = singleLong(args, "Пример: /novel <novel_id>");
        NovelDetails details = novelService.getDetails(chatId, novelId);
        String authors = details.authors().stream()
                .map(author -> author.chatId() + " " + MessageFormatter.username(author.username()) + " - " + author.authorType().displayName())
                .collect(Collectors.joining("\n"));
        return """
                Произведение #%d
                Название: %s
                Жанр: %s
                Описание: %s
                Владелец: %d %s
                Глав: %d
                Создано: %s

                Авторы:
                %s
                """.formatted(
                details.novel().id(),
                details.novel().title(),
                details.novel().genre(),
                details.novel().description(),
                details.owner().chatId(),
                MessageFormatter.username(details.owner().username()),
                details.chapterCount(),
                details.novel().createdAt(),
                authors
        );
    }

    private String deleteNovel(long chatId, String args) {
        long novelId = singleLong(args, "Пример: /delete_novel <novel_id>");
        novelService.deleteNovel(chatId, novelId);
        return "Произведение удалено.";
    }

    private String inviteAuthor(long chatId, String args) {
        long[] values = twoLongs(args, "Пример: /invite_author <novel_id> <chat_id>");
        novelService.inviteAuthor(chatId, values[0], values[1]);
        return "Соавтор добавлен.";
    }

    private String removeAuthor(long chatId, String args) {
        long[] values = twoLongs(args, "Пример: /remove_author <novel_id> <chat_id>");
        novelService.removeAuthor(chatId, values[0], values[1]);
        return "Соавтор удален.";
    }

    private String authors(long chatId, String args) {
        long novelId = singleLong(args, "Пример: /authors <novel_id>");
        List<NovelAuthor> authors = novelService.listAuthors(chatId, novelId);
        return authors.stream()
                .map(author -> "%d %s - %s".formatted(
                        author.chatId(),
                        MessageFormatter.username(author.username()),
                        author.authorType().displayName()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String addChapter(long chatId, String args) {
        String[] parts = pipeArgs(args, 3, "Пример: /add_chapter <novel_id> | <название главы> | <текст главы>");
        long novelId = parseLong(parts[0], "Идентификатор произведения указан неверно.");
        validateText(parts[1], "Название главы", 100);
        validateText(parts[2], "Текст главы", 4000);
        Chapter chapter = chapterService.addChapter(chatId, novelId, parts[1], parts[2]);
        return "Глава добавлена.\nID: " + chapter.id() + "\nНомер: " + chapter.orderNumber() + "\nНазвание: " + chapter.title();
    }

    private String chapters(long chatId, String args) {
        long novelId = singleLong(args, "Пример: /chapters <novel_id>");
        List<Chapter> chapters = chapterService.listChapters(chatId, novelId);
        if (chapters.isEmpty()) {
            return "В произведении пока нет глав.";
        }
        return chapters.stream()
                .map(chapter -> "%d. #%d %s".formatted(chapter.orderNumber(), chapter.id(), chapter.title()))
                .collect(Collectors.joining("\n"));
    }

    private String chapter(long chatId, String args) {
        long[] values = twoLongs(args, "Пример: /chapter <novel_id> <chapter_id>");
        Chapter chapter = chapterService.getChapter(chatId, values[0], values[1]);
        return """
                Глава %d. %s
                ID: %d

                %s
                """.formatted(chapter.orderNumber(), chapter.title(), chapter.id(), chapter.text());
    }

    private String fullText(long chatId, String args) {
        long novelId = singleLong(args, "Пример: /full_text <novel_id>");
        return chapterService.fullText(chatId, novelId);
    }

    private String updateChapter(long chatId, String args) {
        String[] parts = pipeArgs(args, 3, "Пример: /update_chapter <novel_id> <chapter_id> | <новое название> | <новый текст>");
        long[] values = twoLongs(parts[0], "Пример: /update_chapter <novel_id> <chapter_id> | <новое название> | <новый текст>");
        validateText(parts[1], "Название главы", 100);
        validateText(parts[2], "Текст главы", 4000);
        Chapter chapter = chapterService.updateChapter(chatId, values[0], values[1], parts[1], parts[2]);
        return "Глава обновлена.\nID: " + chapter.id() + "\nНазвание: " + chapter.title();
    }

    private String deleteChapter(long chatId, String args) {
        long[] values = twoLongs(args, "Пример: /delete_chapter <novel_id> <chapter_id>");
        chapterService.deleteChapter(chatId, values[0], values[1]);
        return "Глава удалена.";
    }

    private String chapterHistory(long chatId, String args) {
        long[] values = twoLongs(args, "Пример: /chapter_history <novel_id> <chapter_id>");
        List<ChapterHistory> history = chapterService.history(chatId, values[0], values[1]);
        if (history.isEmpty()) {
            return "История изменений главы пока пуста.";
        }
        return history.stream()
                .map(item -> """
                        Версия #%d, изменено: %s, редактор: %d
                        Старое название: %s
                        Фрагмент: %s
                        """.formatted(
                        item.id(),
                        item.changedAt(),
                        item.editorChatId(),
                        item.oldTitle(),
                        MessageFormatter.excerpt(item.oldText(), 180)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String continueChapter(long chatId, String args) {
        long[] values = twoLongs(args, "Пример: /continue_chapter <novel_id> <chapter_id>");
        LlmRequest request = llmRequestService.continueChapter(chatId, values[0], values[1]);
        return accepted(request);
    }

    private String advice(long chatId, String args) {
        String[] parts = splitWhitespace(args, 2, "Пример: /advice <novel_id> <вопрос>");
        long novelId = parseLong(parts[0], "Идентификатор произведения указан неверно.");
        validateText(parts[1], "Вопрос к LLM", 1000);
        LlmRequest request = llmRequestService.advice(chatId, novelId, parts[1]);
        return accepted(request);
    }

    private String draft(long chatId, String args) {
        String[] parts = splitWhitespace(args, 2, "Пример: /draft <novel_id> <запрос>");
        long novelId = parseLong(parts[0], "Идентификатор произведения указан неверно.");
        validateText(parts[1], "Запрос к LLM", 1000);
        LlmRequest request = llmRequestService.draft(chatId, novelId, parts[1]);
        return accepted(request);
    }

    private String requestStatus(long chatId, String args) {
        long requestId = singleLong(args, "Пример: /request_status <request_id>");
        LlmRequest request = llmRequestService.requestStatus(chatId, requestId);
        if (request.status() == LlmRequestStatus.DONE) {
            return "Статус запроса #" + request.id() + ": DONE\n\nРезультат:\n" + request.result();
        }
        if (request.status() == LlmRequestStatus.ERROR) {
            return "Статус запроса #" + request.id() + ": ERROR\nНе удалось получить ответ от языковой модели. Попробуйте позже.";
        }
        return "Статус запроса #" + request.id() + ": " + request.status();
    }

    private String accepted(LlmRequest request) {
        return "Запрос принят в обработку. Номер запроса: " + request.id() + ".";
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

    private String[] pipeArgs(String args, int expected, String usage) {
        String[] parts = Arrays.stream(args.split("\\|", expected))
                .map(String::trim)
                .toArray(String[]::new);
        if (parts.length < expected || Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new UsageException(usage);
        }
        return parts;
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

    private long[] twoLongs(String args, String usage) {
        String[] parts = splitWhitespace(args, 2, usage);
        return new long[]{
                parseLong(parts[0], "Идентификатор произведения указан неверно."),
                parseLong(parts[1], "Идентификатор главы, пользователя или запроса указан неверно.")
        };
    }

    private long parseLong(String value, String error) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new UsageException(error);
        }
    }

    private void validateText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new UsageException(fieldName + " не должно быть пустым.");
        }
        if (value.length() > maxLength) {
            throw new UsageException(fieldName + " не должно быть длиннее " + maxLength + " символов.");
        }
    }

    private record ParsedCommand(String command, String args) {
    }
}
