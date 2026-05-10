package ru.team.novelbot.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.db.DatabaseInitializer;
import ru.team.novelbot.repository.UserRepository;
import ru.team.novelbot.service.UserAuthService;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.document;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration;

@ExtendWith(RestDocumentationExtension.class)
class HttpRoutesRestDocsTest {
    private WebTestClient webTestClient;
    private UserAuthService userAuthService;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        AppProperties properties = testProperties();
        userAuthService = new UserAuthService(new UserRepository(jdbcTemplate), properties);
        HttpRoutes routes = new HttpRoutes(properties, userAuthService);
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
