package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.Novel;
import ru.team.novelbot.domain.NovelAuthor;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.NovelRepository;
import ru.team.novelbot.telegram.MessageFormatter;
import ru.team.novelbot.telegram.TelegramClient;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AdminNovelDeletionService {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final TelegramClient telegramClient;

    public AdminNovelDeletionService(
            NovelRepository novelRepository,
            ChapterRepository chapterRepository,
            TelegramClient telegramClient
    ) {
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.telegramClient = telegramClient;
    }

    public DeleteResult deleteNovel(long novelId, String reason) {
        String normalizedReason = validateReason(reason);
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new AppException("Произведение не найдено."));
        List<NovelAuthor> authors = novelRepository.findAuthors(novelId);
        List<Chapter> chapters = chapterRepository.findByNovelId(novelId);
        String fullText = fullText(novel, authors, chapters, normalizedReason);
        List<Long> failedChatIds = notifyAuthors(novel, authors, normalizedReason, fullText);
        if (!failedChatIds.isEmpty()) {
            throw new NotificationFailedException("Не удалось уведомить всех авторов. Произведение не удалено.", failedChatIds);
        }
        novelRepository.delete(novelId);
        return new DeleteResult(novelId, authors.size());
    }

    private List<Long> notifyAuthors(Novel novel, List<NovelAuthor> authors, String reason, String fullText) {
        List<Long> failedChatIds = new ArrayList<>();
        for (NovelAuthor author : authors) {
            try {
                telegramClient.sendMessageOrThrow(
                        author.chatId(),
                        """
                        Произведение «%s» удаляется администратором.

                        Причина:
                        %s

                        Полный текст отправлен отдельным .txt файлом.
                        """.formatted(novel.title(), reason),
                        List.of()
                );
                telegramClient.sendDocumentOrThrow(author.chatId(), "novel-" + novel.id() + "-deleted.txt", fullText);
            } catch (RuntimeException ex) {
                failedChatIds.add(author.chatId());
            }
        }
        return failedChatIds;
    }

    private String fullText(Novel novel, List<NovelAuthor> authors, List<Chapter> chapters, String reason) {
        String authorsText = authors.stream()
                .map(author -> "- " + author.chatId() + " " + MessageFormatter.username(author.username())
                        + " - " + author.authorType().displayName())
                .collect(Collectors.joining("\n"));
        String chaptersText = chapters.isEmpty()
                ? "В произведении нет глав."
                : chapters.stream()
                .map(chapter -> "Глава " + chapter.orderNumber() + ". " + chapter.title() + "\n\n" + chapter.text())
                .collect(Collectors.joining("\n\n"));
        return """
                Произведение удалено администратором

                Причина удаления:
                %s

                Название: %s
                Жанр: %s
                Описание: %s
                Создано: %s
                Обновлено: %s

                Авторы:
                %s

                Полный текст:

                %s
                """.formatted(
                reason,
                novel.title(),
                novel.genre(),
                novel.description(),
                DATE_FORMAT.format(novel.createdAt()),
                DATE_FORMAT.format(novel.updatedAt()),
                authorsText.isBlank() ? "Нет авторов." : authorsText,
                chaptersText
        );
    }

    private String validateReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            throw new UsageException("Причина удаления обязательна.");
        }
        if (normalized.length() > 1000) {
            throw new UsageException("Причина удаления должна быть не длиннее 1000 символов.");
        }
        return normalized;
    }

    public record DeleteResult(long id, int notifiedAuthors) {
    }

    public static class NotificationFailedException extends AppException {
        private final List<Long> failedChatIds;

        public NotificationFailedException(String message, List<Long> failedChatIds) {
            super(message);
            this.failedChatIds = List.copyOf(failedChatIds);
        }

        public List<Long> failedChatIds() {
            return failedChatIds;
        }
    }
}
