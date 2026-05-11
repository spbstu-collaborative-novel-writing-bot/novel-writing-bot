package ru.team.novelbot.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.llm.LlmClient;
import ru.team.novelbot.repository.LlmRequestRepository;
import ru.team.novelbot.telegram.TelegramButton;
import ru.team.novelbot.telegram.TelegramClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class LlmWorker {
    private static final Logger log = LoggerFactory.getLogger(LlmWorker.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final LlmRequestRepository llmRequestRepository;
    private final LlmClient llmClient;
    private final TelegramClient telegramClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    public LlmWorker(
            AppProperties properties,
            ObjectMapper objectMapper,
            LlmRequestRepository llmRequestRepository,
            LlmClient llmClient,
            TelegramClient telegramClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.llmRequestRepository = llmRequestRepository;
        this.llmClient = llmClient;
        this.telegramClient = telegramClient;
    }

    public void start() {
        running = true;
        executor.submit(this::consumeLoop);
        log.info("LLM RabbitMQ worker started.");
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void consumeLoop() {
        while (running) {
            try {
                ConnectionFactory factory = connectionFactory();
                try (var connection = factory.newConnection();
                     var channel = connection.createChannel()) {
                    channel.queueDeclare(properties.rabbit().queue(), true, false, false, null);
                    channel.basicQos(1);
                    while (running) {
                        GetResponse response = channel.basicGet(properties.rabbit().queue(), false);
                        if (response == null) {
                            sleep(2000);
                            continue;
                        }
                        handle(channel, response);
                    }
                }
            } catch (Exception ex) {
                log.warn("LLM worker временно не работает: {}", ex.getMessage());
                sleep(5000);
            }
        }
    }

    private void handle(Channel channel, GetResponse response) {
        long deliveryTag = response.getEnvelope().getDeliveryTag();
        try {
            String raw = new String(response.getBody(), StandardCharsets.UTF_8);
            LlmTask task = objectMapper.readValue(raw, LlmTask.class);
            LlmRequest request = llmRequestRepository.findById(task.requestId()).orElse(null);
            if (request == null) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            llmRequestRepository.updateStatus(request.id(), LlmRequestStatus.PROCESSING, null, null);
            try {
                String result = llmClient.generate(request.prompt());
                llmRequestRepository.updateStatus(request.id(), LlmRequestStatus.DONE, result, null);
                notifyDone(request, result);
            } catch (Exception ex) {
                String diagnostic = diagnostic(ex);
                llmRequestRepository.updateStatus(
                        request.id(),
                        LlmRequestStatus.ERROR,
                        null,
                        diagnostic
                );
                notifyError(request);
                log.warn("LLM request {} failed: {}", request.id(), diagnostic);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackEx) {
                log.warn("Не удалось подтвердить некорректное RabbitMQ-сообщение: {}", ackEx.getMessage());
            }
            log.warn("Некорректное сообщение LLM worker: {}", ex.getMessage());
        }
    }

    private void notifyDone(LlmRequest request, String result) {
        String preview = result.length() > 3000
                ? result.substring(0, 2800) + "\n\nРезультат длинный, полный текст отправлен .txt файлом."
                : result;
        List<List<TelegramButton>> keyboard = request.chapterId() == null
                ? List.of()
                : List.of(List.of(TelegramButton.callback("Добавить в конец главы", "llm:add:" + request.id())));
        telegramClient.sendMessage(
                request.chatId(),
                "LLM-запрос #" + request.id() + " готов.\n\n" + preview,
                keyboard
        );
        if (result.length() > 3000) {
            telegramClient.sendDocument(request.chatId(), "llm-request-" + request.id() + ".txt", result);
        }
    }

    private void notifyError(LlmRequest request) {
        telegramClient.sendMessage(
                request.chatId(),
                "LLM-запрос #" + request.id() + " завершился ошибкой. Упс... LLM сейчас недоступен. Попробуйте позже."
        );
    }

    private String diagnostic(Exception ex) {
        Throwable current = ex;
        Throwable last = ex;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        String message = last.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message
                .replaceAll("(?i)(authorization|token|key|secret|password)=[^\\s,;]+", "$1=***")
                .replaceAll("(?i)(Bearer|Basic)\\s+[^\\s,;]+", "$1 ***")
                .trim();
    }

    private ConnectionFactory connectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.rabbit().host());
        factory.setPort(properties.rabbit().port());
        factory.setUsername(properties.rabbit().username());
        factory.setPassword(properties.rabbit().password());
        factory.setAutomaticRecoveryEnabled(true);
        return factory;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
