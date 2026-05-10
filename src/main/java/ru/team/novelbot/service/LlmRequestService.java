package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.domain.LlmRequestType;
import ru.team.novelbot.domain.Novel;
import ru.team.novelbot.rabbit.LlmTask;
import ru.team.novelbot.rabbit.LlmTaskPublisher;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.LlmRequestRepository;
import ru.team.novelbot.repository.NovelRepository;

@Service
public class LlmRequestService {
    private final AppProperties properties;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final LlmRequestRepository llmRequestRepository;
    private final AccessControlService accessControlService;
    private final LlmTaskPublisher taskPublisher;

    public LlmRequestService(
            AppProperties properties,
            NovelRepository novelRepository,
            ChapterRepository chapterRepository,
            LlmRequestRepository llmRequestRepository,
            AccessControlService accessControlService,
            LlmTaskPublisher taskPublisher
    ) {
        this.properties = properties;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.llmRequestRepository = llmRequestRepository;
        this.accessControlService = accessControlService;
        this.taskPublisher = taskPublisher;
    }

    public LlmRequest continueChapter(long chatId, long novelId, long chapterId) {
        ensureLlmEnabled();
        accessControlService.requireAccess(novelId, chatId);
        Novel novel = requireNovel(novelId);
        Chapter chapter = chapterRepository.findByIdAndNovelId(chapterId, novelId)
                .orElseThrow(() -> new AppException("Глава не найдена."));
        String prompt = """
                Продолжи главу литературного произведения.

                Название: %s
                Жанр: %s
                Описание: %s
                Глава: %s
                Последний фрагмент главы:
                %s

                Сгенерируй связное продолжение в том же стиле.
                """.formatted(
                novel.title(),
                novel.genre(),
                novel.description(),
                chapter.title(),
                TextTools.tail(chapter.text(), 1800)
        );
        return createAndPublish(chatId, novelId, chapterId, LlmRequestType.CONTINUE_CHAPTER, prompt);
    }

    public LlmRequest advice(long chatId, long novelId, String question) {
        ensureLlmEnabled();
        accessControlService.requireAccess(novelId, chatId);
        Novel novel = requireNovel(novelId);
        String prompt = """
                Дай авторский совет по произведению.

                Название: %s
                Жанр: %s
                Описание: %s
                Вопрос автора: %s

                Ответь кратко и практично.
                """.formatted(novel.title(), novel.genre(), novel.description(), question);
        return createAndPublish(chatId, novelId, null, LlmRequestType.ADVICE, prompt);
    }

    public LlmRequest draft(long chatId, long novelId, String request) {
        ensureLlmEnabled();
        accessControlService.requireAccess(novelId, chatId);
        Novel novel = requireNovel(novelId);
        String prompt = """
                Создай черновик новой главы для произведения.

                Название: %s
                Жанр: %s
                Описание: %s
                Запрос автора: %s

                Верни только текст черновика.
                """.formatted(novel.title(), novel.genre(), novel.description(), request);
        return createAndPublish(chatId, novelId, null, LlmRequestType.DRAFT, prompt);
    }

    public LlmRequest requestStatus(long chatId, long requestId) {
        LlmRequest request = llmRequestRepository.findById(requestId)
                .orElseThrow(() -> new AppException("LLM-запрос не найден."));
        if (request.chatId() != chatId) {
            throw new AccessDeniedException("Нет доступа к этому LLM-запросу.");
        }
        return request;
    }

    private LlmRequest createAndPublish(
            long chatId,
            long novelId,
            Long chapterId,
            LlmRequestType requestType,
            String prompt
    ) {
        LlmRequest request = llmRequestRepository.create(chatId, novelId, chapterId, requestType, prompt);
        try {
            taskPublisher.publish(new LlmTask(request.id(), request.requestType().name()));
            return request;
        } catch (RuntimeException ex) {
            llmRequestRepository.updateStatus(
                    request.id(),
                    LlmRequestStatus.ERROR,
                    null,
                    "RabbitMQ недоступен: " + ex.getClass().getSimpleName()
            );
            throw new AppException("Не удалось поставить LLM-запрос в очередь. Попробуйте позже.", ex);
        }
    }

    private Novel requireNovel(long novelId) {
        return novelRepository.findById(novelId)
                .orElseThrow(() -> new AppException("Произведение не найдено."));
    }

    private void ensureLlmEnabled() {
        if (!properties.llm().enabled()) {
            throw new AppException("LLM-функции недоступны: не задан LLM_API_KEY.");
        }
    }
}
