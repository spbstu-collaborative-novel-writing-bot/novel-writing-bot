package ru.team.novelbot.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.db.DatabaseInitializer;
import ru.team.novelbot.rabbit.LlmTaskPublisher;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.LlmRequestRepository;
import ru.team.novelbot.repository.NovelRepository;
import ru.team.novelbot.repository.TelegramSessionRepository;
import ru.team.novelbot.repository.UserRepository;
import ru.team.novelbot.service.AccessControlService;
import ru.team.novelbot.service.ChapterService;
import ru.team.novelbot.service.LlmRequestService;
import ru.team.novelbot.service.NovelService;
import ru.team.novelbot.service.UserAuthService;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRouterTest {
    private CommandRouter router;
    private UserAuthService userAuthService;
    private NovelService novelService;
    private ChapterService chapterService;
    private TelegramSessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AppProperties properties = testProperties("https://example.com/mini/chapter-editor");
        UserRepository userRepository = new UserRepository(jdbcTemplate);
        NovelRepository novelRepository = new NovelRepository(jdbcTemplate);
        ChapterRepository chapterRepository = new ChapterRepository(jdbcTemplate);
        LlmRequestRepository llmRequestRepository = new LlmRequestRepository(jdbcTemplate);
        sessionRepository = new TelegramSessionRepository(jdbcTemplate);
        AccessControlService accessControlService = new AccessControlService(novelRepository);
        userAuthService = new UserAuthService(userRepository, properties);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        chapterService = new ChapterService(chapterRepository, novelRepository, accessControlService, tx);
        novelService = new NovelService(novelRepository, chapterRepository, userAuthService, accessControlService, tx);
        LlmTaskPublisher publisher = task -> {
        };
        router = new CommandRouter(
                properties,
                objectMapper,
                userAuthService,
                novelService,
                chapterService,
                new LlmRequestService(properties, novelRepository, chapterRepository, llmRequestRepository, accessControlService, publisher),
                sessionRepository,
                new TelegramClient(properties, HttpClient.newHttpClient(), objectMapper)
        );
    }

    @Test
    void newCommandStartsWizard() {
        TelegramResponse response = router.route(TelegramInboundMessage.text(100, "writer", "Writer", "/new"));

        assertThat(response.actions()).anyMatch(action -> action.text().contains("Введите название романа"));
        assertThat(sessionRepository.findByChatId(100)).hasValueSatisfying(session ->
                assertThat(session.state()).isEqualTo("CREATE_NOVEL_TITLE"));
    }

    @Test
    void novelsCommandReturnsInlineButtons() {
        userAuthService.registerOrUpdate(100, "writer", "Writer");
        novelService.createNovel(100, "City", "Story", "fantasy");

        TelegramResponse response = router.route(TelegramInboundMessage.text(100, "writer", "Writer", "/novels"));

        TelegramAction action = response.actions().getFirst();
        assertThat(action.text()).contains("Ваши романы");
        assertThat(flatButtons(action.keyboard())).anyMatch(button -> button.callbackData().startsWith("novel:"));
    }

    @Test
    void chapterCardIncludesWebAppButtonWhenConfigured() {
        userAuthService.registerOrUpdate(100, "writer", "Writer");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        var chapter = chapterService.addChapter(100, novel.id(), "Start", "Text");
        TelegramInboundMessage callback = new TelegramInboundMessage(
                TelegramUpdateType.CALLBACK,
                100,
                "writer",
                "Writer",
                null,
                false,
                "callback-id",
                55,
                "chapter:" + novel.id() + ":" + chapter.id(),
                null,
                null,
                null
        );

        TelegramResponse response = router.route(callback);

        TelegramAction edit = response.actions().stream()
                .filter(action -> action.type() == TelegramActionType.EDIT_MESSAGE)
                .findFirst()
                .orElseThrow();
        assertThat(flatButtons(edit.keyboard())).anyMatch(button ->
                button.webAppUrl() != null && button.webAppUrl().contains("novelId=" + novel.id()));
    }

    private List<TelegramButton> flatButtons(List<List<TelegramButton>> keyboard) {
        return keyboard.stream().flatMap(List::stream).toList();
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:telegram-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private AppProperties testProperties(String webAppUrl) {
        return new AppProperties(
                "telegram-token",
                webAppUrl,
                Set.of(100L),
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm("OPENAI_COMPATIBLE", "llm-key", "http://localhost/llm", "test-model", "", "GIGACHAT_API_PERS", "http://localhost/oauth"),
                List.of("Гвоздева Е.", "Крутиков Д.", "Михайлова А.", "Романова А.")
        );
    }
}
