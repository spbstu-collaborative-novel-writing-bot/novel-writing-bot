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
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.NovelService;
import ru.team.novelbot.service.UserAuthService;
import ru.team.novelbot.telegram.MiniAppAuthService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        userAuthService = new UserAuthService(userRepository, properties);
        chapterService = new ChapterService(
                chapterRepository,
                novelRepository,
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
                userAuthService,
                chapterService,
                llmRequestService,
                new AdminStatsService(jdbcTemplate),
                new MiniAppAuthService(properties, objectMapper),
                objectMapper
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
    void documentsUsersForbidden() {
        webTestClient.get()
                .uri("/users")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .consumeWith(document(
                        "users-forbidden",
                        responseFields(fieldWithPath("error").description("Код ошибки доступа."))
                ));
    }

    @Test
    void documentsUsersSuccess() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");

        webTestClient.get()
                .uri("/users")
                .header("X-Admin-Token", "secret")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(document(
                        "users-success",
                        requestHeaders(headerWithName("X-Admin-Token").description("Административный HTTP-токен.")),
                        responseFields(
                                fieldWithPath("[].chat_id").description("Telegram chat_id пользователя."),
                                fieldWithPath("[].username").description("Username Telegram, если доступен."),
                                fieldWithPath("[].role").description("Системная роль пользователя."),
                                fieldWithPath("[].created_at").description("Дата и время регистрации.")
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
                .consumeWith(result -> assertThat(result.getResponseBody()).contains("Продолжить главу"));
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
                .consumeWith(result -> assertThat(result.getResponseBody()).contains("\"users\":1", "\"novels\":1"));
    }

    @Test
    void rejectsMiniApiWithoutTelegramInitData() {
        webTestClient.get()
                .uri("/mini/api/chapters/1/1")
                .exchange()
                .expectStatus().isForbidden();
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
                Set.of(100L),
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm("OPENAI_COMPATIBLE", "llm-key", "http://localhost/llm", "test-model", "", "GIGACHAT_API_PERS", "http://localhost/oauth"),
                List.of("Гвоздева Е.", "Крутиков Д.", "Михайлова А.", "Романова А.")
        );
    }

    private String signedInitData(long chatId) throws Exception {
        String user = "{\"id\":" + chatId + ",\"first_name\":\"Owner\"}";
        String authDate = "1710000000";
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
}
