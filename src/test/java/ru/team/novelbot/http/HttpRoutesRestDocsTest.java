package ru.team.novelbot.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.db.DatabaseInitializer;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.LlmRequestRepository;
import ru.team.novelbot.repository.NovelRepository;
import ru.team.novelbot.repository.UserRepository;
import ru.team.novelbot.service.AccessControlService;
import ru.team.novelbot.service.AdminStatsService;
import ru.team.novelbot.service.AdminNovelDeletionService;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.NovelService;
import ru.team.novelbot.service.UserAuthService;
import ru.team.novelbot.telegram.MiniAppAuthService;
import ru.team.novelbot.telegram.TelegramButton;
import ru.team.novelbot.telegram.TelegramClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.document;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(RestDocumentationExtension.class)
class HttpRoutesRestDocsTest {
    private WebTestClient webTestClient;
    private UserAuthService userAuthService;
    private NovelService novelService;
    private ChapterService chapterService;
    private AppProperties properties;
    private ObjectMapper objectMapper;
    private RecordingTelegramClient telegramClient;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        properties = testProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        UserRepository userRepository = new UserRepository(jdbcTemplate);
        NovelRepository novelRepository = new NovelRepository(jdbcTemplate);
        ChapterRepository chapterRepository = new ChapterRepository(jdbcTemplate);
        LlmRequestRepository llmRequestRepository = new LlmRequestRepository(jdbcTemplate);
        AccessControlService accessControlService = new AccessControlService(novelRepository);
        userAuthService = new UserAuthService(userRepository);
        telegramClient = new RecordingTelegramClient(properties, objectMapper);
        chapterService = new ChapterService(
                chapterRepository,
                novelRepository,
                userRepository,
                accessControlService,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        novelService = new NovelService(
                novelRepository,
                chapterRepository,
                userAuthService,
                accessControlService,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        LlmRequestService llmRequestService = new LlmRequestService(
                properties,
                novelRepository,
                chapterRepository,
                llmRequestRepository,
                accessControlService,
                task -> {
                }
        );
        HttpRoutes routes = new HttpRoutes(
                properties,
                chapterService,
                llmRequestService,
                new AdminStatsService(jdbcTemplate),
                new AdminNovelDeletionService(novelRepository, chapterRepository, telegramClient),
                new MiniAppAuthService(properties, objectMapper)
        );
        webTestClient = WebTestClient.bindToRouterFunction(routes.routes())
                .configureClient()
                .filter(documentationConfiguration(restDocumentation))
                .build();
    }

    @Test
    void documentsHealthcheck() {
        webTestClient.get()
                .uri("/healthcheck")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(document(
                        "healthcheck",
                        responseFields(
                                fieldWithPath("status").description("Статус приложения."),
                                fieldWithPath("project").description("Название проекта."),
                                fieldWithPath("authors").description("Список авторов проекта."),
                                fieldWithPath("time").description("Текущая дата и время сервера.")
                        )
                ));
    }

    @Test
    void documentsAdminUsersSuccess() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");

        webTestClient.get()
                .uri("/admin/api/users")
                .header("X-Admin-Token", "secret")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(document(
                        "admin-users-success",
                        requestHeaders(headerWithName("X-Admin-Token").description("Административный HTTP-токен.")),
                        responseFields(
                                fieldWithPath("[].chat_id").description("Telegram chat_id пользователя."),
                                fieldWithPath("[].username").description("Username Telegram, если доступен."),
                                fieldWithPath("[].display_name").description("Отображаемое имя пользователя."),
                                fieldWithPath("[].created_at").description("Дата и время регистрации."),
                                fieldWithPath("[].accessible_novels").description("Количество доступных произведений."),
                                fieldWithPath("[].owned_novels").description("Количество произведений, где пользователь владелец.")
                        )
                ));
    }

    @Test
    void servesMiniAppEditorHtml() {
        webTestClient.get()
                .uri("/mini/chapter-editor")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class)
                .consumeWith(result -> {
                    String body = result.getResponseBody();
                    assertThat(body).contains("Продолжить главу", "editor-toolbar", "История", "Упс... LLM сейчас недоступен");
                    assertThat(body.indexOf("id=\"save\"")).isLessThan(body.indexOf("id=\"title\""));
                });
    }

    @Test
    void servesAdminPanelHtml() {
        webTestClient.get()
                .uri("/admin")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody()).contains("Админ-панель бота"));
    }

    @Test
    void protectsAdminApiAndReturnsOverview() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        novelService.createNovel(100, "City", "Story", "fantasy");

        webTestClient.get()
                .uri("/admin/api/overview")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get()
                .uri("/admin/api/overview")
                .header("X-Admin-Token", "secret")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .contains("\"users\":1", "\"novels\":1")
                        .doesNotContain("\"admins\""));
    }

    @Test
    void adminNovelsUsesOwnerChatIdField() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        novelService.createNovel(100, "City", "Story", "fantasy");

        webTestClient.get()
                .uri("/admin/api/novels")
                .header("X-Admin-Token", "secret")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .contains("\"owner_chat_id\":100")
                        .doesNotContain("creator_chat_id"));
    }

    @Test
    void adminDeletesNovelAfterNotifyingAllAuthors() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "coauthor", "Co Author");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        novelService.inviteAuthor(100, novel.id(), 200);
        chapterService.addChapter(100, novel.id(), "Start", "Original text");

        webTestClient.post()
                .uri("/admin/api/novels/{novelId}/delete", novel.id())
                .header("X-Admin-Token", "secret")
                .bodyValue(Map.of("reason", "Нарушение правил публикации."))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .contains("\"deleted\":true", "\"notified_authors\":2"));

        assertThat(novelService.listAccessible(100)).isEmpty();
        assertThat(telegramClient.messages).hasSize(2)
                .allSatisfy(message -> assertThat(message.text()).contains("Нарушение правил публикации."));
        assertThat(telegramClient.documents).hasSize(2)
                .allSatisfy(document -> assertThat(document.text()).contains("Original text"));
    }

    @Test
    void adminDeletionDoesNotDeleteNovelWhenNotificationFails() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "coauthor", "Co Author");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        novelService.inviteAuthor(100, novel.id(), 200);
        telegramClient.failedChatIds.add(200L);

        webTestClient.post()
                .uri("/admin/api/novels/{novelId}/delete", novel.id())
                .header("X-Admin-Token", "secret")
                .bodyValue(Map.of("reason", "Нарушение правил публикации."))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .contains("failed_chat_ids", "200"));

        assertThat(novelService.listAccessible(100)).hasSize(1);
    }

    @Test
    void rejectsMiniApiWithoutTelegramInitData() {
        webTestClient.get()
                .uri("/mini/api/chapters/1/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsMiniApiWithInvalidPathId() throws Exception {
        webTestClient.get()
                .uri("/mini/api/chapters/not-a-number/1")
                .header("X-Telegram-Init-Data", signedInitData(100))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody()).contains("Некорректный id"));
    }

    @Test
    void rejectsExpiredMiniAppInitData() throws Exception {
        long expiredAuthDate = Instant.now().minus(Duration.ofHours(25)).getEpochSecond();

        webTestClient.get()
                .uri("/mini/api/chapters/1/1")
                .header("X-Telegram-Init-Data", signedInitData(100, expiredAuthDate))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody()).contains("устарели"));
    }

    @Test
    void miniApiLoadsAndSavesChapterWithValidInitData() throws Exception {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        var chapter = chapterService.addChapter(100, novel.id(), "Start", "Original text");
        String initData = signedInitData(100);

        String loaded = webTestClient.get()
                .uri("/mini/api/chapters/{novelId}/{chapterId}", novel.id(), chapter.id())
                .header("X-Telegram-Init-Data", initData)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(loaded).contains("\"title\":\"Start\"");

        String saved = webTestClient.put()
                .uri("/mini/api/chapters/{novelId}/{chapterId}", novel.id(), chapter.id())
                .header("X-Telegram-Init-Data", initData)
                .bodyValue(Map.of(
                        "title", "Renamed",
                        "text", "Updated text",
                        "updated_at", chapter.updatedAt().toString()
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(saved).contains("\"title\":\"Renamed\"");
        assertThat(saved).contains("\"text\":\"Updated text\"");
    }

    @Test
    void miniApiListsVersionsAndLoadsDiff() throws Exception {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        var chapter = chapterService.addChapter(100, novel.id(), "Start", "Original text");
        chapterService.updateChapter(100, novel.id(), chapter.id(), "Start", "Updated text");
        String initData = signedInitData(100);

        String versions = webTestClient.get()
                .uri("/mini/api/chapters/{novelId}/{chapterId}/versions?page=0&size=20", novel.id(), chapter.id())
                .header("X-Telegram-Init-Data", initData)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(versions)
                .contains("\"total\":2", "\"version_number\":2", "\"is_current\":true", "\"editor_name\":\"@owner\"");

        String diff = webTestClient.get()
                .uri("/mini/api/chapters/{novelId}/{chapterId}/versions/{versionNumber}/diff", novel.id(), chapter.id(), 2)
                .header("X-Telegram-Init-Data", initData)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(diff)
                .contains("\"version_number\":2", "\"type\":\"removed\"", "\"text\":\"Original\"", "\"type\":\"added\"", "\"text\":\"Updated\"");
    }

    @Test
    void miniApiCreatesAndLoadsLlmRequest() throws Exception {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        var chapter = chapterService.addChapter(100, novel.id(), "Start", "Original text");
        String initData = signedInitData(100);

        String created = webTestClient.post()
                .uri("/mini/api/chapters/{novelId}/{chapterId}/llm", novel.id(), chapter.id())
                .header("X-Telegram-Init-Data", initData)
                .bodyValue(Map.of("type", "ADVICE", "prompt", "Что усилить в сцене?"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).contains("\"request_type\":\"ADVICE\"", "\"status\":\"QUEUED\"");
        long requestId = objectMapper.readTree(created).path("id").asLong();

        webTestClient.get()
                .uri("/mini/api/llm/{requestId}", requestId)
                .header("X-Telegram-Init-Data", initData)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody()).contains("\"id\":" + requestId));
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:http-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private AppProperties testProperties() {
        return new AppProperties(
                "telegram-token",
                "",
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm("OPENAI_COMPATIBLE", "llm-key", "http://localhost/llm", "test-model", "", "GIGACHAT_API_PERS", "http://localhost/oauth", "", true),
                List.of("Гвоздева Е.", "Крутиков Д.", "Михайлова А.", "Романова А.")
        );
    }

    private String signedInitData(long chatId) throws Exception {
        return signedInitData(chatId, Instant.now().getEpochSecond());
    }

    private String signedInitData(long chatId, long authDateEpochSecond) throws Exception {
        String user = "{\"id\":" + chatId + ",\"first_name\":\"Owner\"}";
        String authDate = Long.toString(authDateEpochSecond);
        String queryId = "test-query";
        String dataCheckString = "auth_date=" + authDate + "\n"
                + "query_id=" + queryId + "\n"
                + "user=" + user;
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), properties.telegramBotToken().getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(hmac(secret, dataCheckString.getBytes(StandardCharsets.UTF_8)));
        return "auth_date=" + authDate
                + "&query_id=" + queryId
                + "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&hash=" + hash;
    }

    private byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static final class RecordingTelegramClient extends TelegramClient {
        private final List<SentMessage> messages = new ArrayList<>();
        private final List<SentDocument> documents = new ArrayList<>();
        private final List<Long> failedChatIds = new ArrayList<>();

        private RecordingTelegramClient(AppProperties properties, ObjectMapper objectMapper) {
            super(properties, HttpClient.newHttpClient(), objectMapper);
        }

        @Override
        public void sendMessageOrThrow(long chatId, String text, List<List<TelegramButton>> keyboard) {
            if (failedChatIds.contains(chatId)) {
                throw new IllegalStateException("telegram unavailable");
            }
            messages.add(new SentMessage(chatId, text));
        }

        @Override
        public void sendDocumentOrThrow(long chatId, String filename, String text) {
            if (failedChatIds.contains(chatId)) {
                throw new IllegalStateException("telegram unavailable");
            }
            documents.add(new SentDocument(chatId, filename, text));
        }
    }

    private record SentMessage(long chatId, String text) {
    }

    private record SentDocument(long chatId, String filename, String text) {
    }
}
