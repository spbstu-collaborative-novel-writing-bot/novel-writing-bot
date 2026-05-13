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
import ru.team.novelbot.domain.UserRole;
import ru.team.novelbot.rabbit.LlmTask;
import ru.team.novelbot.rabbit.LlmTaskPublisher;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.LlmRequestRepository;
import ru.team.novelbot.repository.NovelRepository;
import ru.team.novelbot.repository.UserRepository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

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
        userAuthService = new UserAuthService(properties, userRepository);
        chapterService = new ChapterService(chapterRepository, novelRepository, userRepository, accessControlService, transactionTemplate);
        novelService = new NovelService(novelRepository, chapterRepository, userAuthService, accessControlService, transactionTemplate);
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
        assertThat(userAuthService.requireUser(100).role()).isEqualTo(UserRole.USER);
    }

    @Test
    void assignsAdminRoleFromConfiguredTelegramChatIds() {
        AppProperties properties = new AppProperties(
                "telegram-token",
                "",
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm("OPENAI_COMPATIBLE", "llm-key", "http://localhost/llm", "test-model", "", "GIGACHAT_API_PERS", "http://localhost/oauth", "", true),
                List.of(100L),
                List.of("author")
        );
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        UserAuthService authService = new UserAuthService(properties, new UserRepository(jdbcTemplate));

        var user = authService.registerOrUpdate(100, "admin", "Admin");

        assertThat(user.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void roleAssignedInDatabaseSurvivesNextTelegramProfileUpdate() {
        userAuthService.registerOrUpdate(100, "writer", "Writer");
        userAuthService.updateRole(100, UserRole.ADMIN);

        var user = userAuthService.registerOrUpdate(100, "writer_new", "Writer New");

        assertThat(user.role()).isEqualTo(UserRole.ADMIN);
        assertThat(user.username()).isEqualTo("writer_new");
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
    void managesAdditionalOwnersAndCoAuthors() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "owner2", "Owner Two");
        userAuthService.registerOrUpdate(300, "coauthor", "Co Author");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        novelService.addAuthor(100, novel.id(), 200, AuthorType.OWNER);
        novelService.addAuthor(200, novel.id(), 300, AuthorType.CO_AUTHOR);

        assertThat(novelRepository.findAuthorType(novel.id(), 200)).contains(AuthorType.OWNER);
        assertThat(novelRepository.findAuthorType(novel.id(), 300)).contains(AuthorType.CO_AUTHOR);
        assertThat(novelService.getDetails(300, novel.id()).novel().title()).isEqualTo("Город");
    }

    @Test
    void addsAuthorsByTelegramTag() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "CoAuthor", "Co Author");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        novelService.addAuthor(100, novel.id(), "@coauthor", AuthorType.CO_AUTHOR);

        assertThat(novelRepository.findAuthorType(novel.id(), 200)).contains(AuthorType.CO_AUTHOR);
    }

    @Test
    void rejectsUnknownAndDuplicateAuthors() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "coauthor", "Co Author");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        novelService.addAuthor(100, novel.id(), 200, AuthorType.CO_AUTHOR);

        assertThatThrownBy(() -> novelService.addAuthor(100, novel.id(), 999, AuthorType.CO_AUTHOR))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("не найден");
        assertThatThrownBy(() -> novelService.addAuthor(100, novel.id(), 200, AuthorType.OWNER))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("уже добавлен");
    }

    @Test
    void protectsLastOwnerAndAllowsAdditionalOwnerToDeleteNovel() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        userAuthService.registerOrUpdate(200, "owner2", "Owner Two");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");

        assertThatThrownBy(() -> novelService.removeAuthor(100, novel.id(), 100))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Последнего владельца");

        novelService.addAuthor(100, novel.id(), 200, AuthorType.OWNER);
        novelService.deleteNovel(200, novel.id());

        assertThat(novelRepository.exists(novel.id())).isFalse();
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
    void storesAllChapterVersionsAndBuildsDiff() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");
        var chapter = chapterService.addChapter(100, novel.id(), "Версия 0", "Текст 0");

        for (int i = 1; i <= 6; i++) {
            chapterService.updateChapter(100, novel.id(), chapter.id(), "Версия " + i, "Текст " + i);
        }

        var history = chapterService.history(100, novel.id(), chapter.id());
        assertThat(history).hasSize(6);

        var page = chapterService.versions(100, novel.id(), chapter.id(), 0, 20);
        assertThat(page.total()).isEqualTo(7);
        assertThat(page.versions().getFirst().versionNumber()).isEqualTo(7);
        assertThat(page.versions().getFirst().current()).isTrue();
        assertThat(page.versions().getFirst().editorName()).isEqualTo("@owner");
        assertThat(page.versions()).anySatisfy(version -> {
            assertThat(version.versionNumber()).isEqualTo(1);
            assertThat(version.title()).isEqualTo("Версия 0");
            assertThat(version.text()).isEqualTo("Текст 0");
        });

        var firstDiff = chapterService.versionDiff(100, novel.id(), chapter.id(), 1);
        assertThat(firstDiff.firstVersion()).isTrue();
        assertThat(firstDiff.textFragments()).flatExtracting(fragment -> fragment.parts())
                .anySatisfy(part -> {
                    assertThat(part.type()).isEqualTo("added");
                    assertThat(part.text()).contains("Текст 0");
                });

        var currentDiff = chapterService.versionDiff(100, novel.id(), chapter.id(), 7);
        assertThat(currentDiff.textFragments()).flatExtracting(fragment -> fragment.parts())
                .anySatisfy(part -> {
                    assertThat(part.type()).isEqualTo("removed");
                    assertThat(part.text()).contains("5");
                })
                .anySatisfy(part -> {
                    assertThat(part.type()).isEqualTo("added");
                    assertThat(part.text()).contains("6");
                });
    }

    @Test
    void createsQueuedLlmRequestAndPublishesTask() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "Город", "История города", "фантастика");
        var chapter = chapterService.addChapter(100, novel.id(), "Начало", "Текст главы");

        var request = llmRequestService.continueChapter(100, novel.id(), chapter.id());

        assertThat(request.status()).isEqualTo(LlmRequestStatus.QUEUED);
        assertThat(request.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(request.model()).isEqualTo("test-model");
        assertThat(publishedTasks).containsExactly(new LlmTask(request.id(), "CONTINUE_CHAPTER"));
    }

    @Test
    void calculatesNovelStats() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        chapterService.addChapter(100, novel.id(), "One", "one two three");
        chapterService.addChapter(100, novel.id(), "Two", "four five");

        var details = novelService.getDetails(100, novel.id());

        assertThat(details.stats().chapterCount()).isEqualTo(2);
        assertThat(details.stats().wordCount()).isEqualTo(5);
        assertThat(details.stats().characterCount()).isEqualTo("one two three".length() + "four five".length());
    }

    @Test
    void detectsStaleChapterUpdate() {
        userAuthService.registerOrUpdate(100, "owner", "Owner");
        var novel = novelService.createNovel(100, "City", "Story", "fantasy");
        var chapter = chapterService.addChapter(100, novel.id(), "One", "first");

        chapterService.updateChapter(100, novel.id(), chapter.id(), "One", "changed");
        var result = chapterService.updateChapterIfUnchanged(
                100,
                novel.id(),
                chapter.id(),
                "One",
                "stale",
                chapter.updatedAt()
        );

        assertThat(result.saved()).isFalse();
        assertThat(result.chapter().text()).isEqualTo("changed");
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
                "",
                "secret",
                8080,
                new AppProperties.Database("localhost", 5432, "novelbot", "user", "password"),
                new AppProperties.Rabbit("localhost", 5672, "guest", "guest", "llm.requests"),
                new AppProperties.Llm("OPENAI_COMPATIBLE", "llm-key", "http://localhost/llm", "test-model", "", "GIGACHAT_API_PERS", "http://localhost/oauth", "", true),
                List.of(),
                List.of("Гвоздева Е.", "Крутиков Д.", "Михайлова А.", "Романова А.")
        );
    }
}
