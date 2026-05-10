package ru.team.novelbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.db.DatabaseInitializer;
import ru.team.novelbot.domain.AuthorType;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.rabbit.LlmTask;
import ru.team.novelbot.rabbit.LlmTaskPublisher;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.LlmRequestRepository;
import ru.team.novelbot.repository.NovelRepository;
import ru.team.novelbot.repository.UserRepository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceIntegrationTest {
    private UserAuthService userAuthService;
    private NovelService novelService;
    private ChapterService chapterService;
    private LlmRequestService llmRequestService;
    private NovelRepository novelRepository;
    private List<LlmTask> publishedTasks;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();

        AppProperties properties = testProperties();
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UserRepository userRepository = new UserRepository(jdbcTemplate);
        novelRepository = new NovelRepository(jdbcTemplate);
        ChapterRepository chapterRepository = new ChapterRepository(jdbcTemplate);
        LlmRequestRepository llmRequestRepository = new LlmRequestRepository(jdbcTemplate);
        AccessControlService accessControlService = new AccessControlService(novelRepository);
        userAuthService = new UserAuthService(userRepository, properties);
        novelService = new NovelService(novelRepository, userAuthService, accessControlService, transactionTemplate);
        chapterService = new ChapterService(chapterRepository, novelRepository, accessControlService, transactionTemplate);
        publishedTasks = new ArrayList<>();
        LlmTaskPublisher publisher = publishedTasks::add;
        llmRequestService = new LlmRequestService(
                properties,
                novelRepository,
                chapterRepository,
                llmRequestRepository,
                accessControlService,
                publisher
        );
    }

    @Test
    void createsUserOnFirstContactWithoutDuplicates() {
        userAuthService.registerOrUpdate(100, "writer", "Writer One");
        userAuthService.registerOrUpdate(100, "writer_new", "Writer One");

        assertThat(userAuthService.findAll()).hasSize(1);
        assertThat(userAuthService.requireUser(100).username()).isEqualTo("writer_new");
    }

    @Test
    void createsNovelAndAssignsOwner() {
        userAuthService.registerOrUpdate(100, "writer", "Writer One");

        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        assertThat(novel.id()).isPositive();
        assertThat(novelRepository.findAuthorType(novel.id(), 100)).contains(AuthorType.OWNER);
    }

    @Test
    void checksAccessForOwnerCoAuthorAndForeignUser() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "coauthor", "Co Author");
        userAuthService.registerOrUpdate(300, "stranger", "Stranger");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        novelService.inviteAuthor(100, novel.id(), 200);

        assertThat(novelService.getDetails(200, novel.id()).novel().title()).isEqualTo("Город");
        assertThatThrownBy(() -> novelService.getDetails(300, novel.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addsChapterToAccessibleNovel() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        var chapter = chapterService.addChapter(100, novel.id(), "Начало", "Первый текст");

        assertThat(chapter.id()).isPositive();
        assertThat(chapter.orderNumber()).isEqualTo(1);
    }

    @Test
    void storesChapterHistoryOnUpdate() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");
        var chapter = chapterService.addChapter(100, novel.id(), "Начало", "Первый текст");

        chapterService.updateChapter(100, novel.id(), chapter.id(), "Новое начало", "Новый текст");

        var history = chapterService.history(100, novel.id(), chapter.id());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().oldTitle()).isEqualTo("Начало");
        assertThat(history.getFirst().oldText()).isEqualTo("Первый текст");
    }

    @Test
    void createsQueuedLlmRequestAndPublishesTask() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");
        var chapter = chapterService.addChapter(100, novel.id(), "Начало", "Текст главы");

        var request = llmRequestService.continueChapter(100, novel.id(), chapter.id());

        assertThat(request.status()).isEqualTo(LlmRequestStatus.QUEUED);
        assertThat(publishedTasks).containsExactly(new LlmTask(request.id(), "CONTINUE_CHAPTER"));
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:novelbot-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private AppProperties testProperties() {
        return new AppProperties(
                "telegram-token",
                Set.of(100L),
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm("llm-key", "http://localhost/llm", "test-model"),
                List.of("Гвоздева Е.", "Крутиков Д.", "Михайлова А.", "Романова А.")
        );
    }
}
