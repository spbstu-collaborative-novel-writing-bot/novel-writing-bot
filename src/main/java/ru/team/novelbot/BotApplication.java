package ru.team.novelbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import ru.team.novelbot.config.AppConfig;
import ru.team.novelbot.db.DatabaseInitializer;
import ru.team.novelbot.http.HttpServer;
import ru.team.novelbot.rabbit.LlmWorker;
import ru.team.novelbot.telegram.TelegramBotAdapter;

public final class BotApplication {
    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    private BotApplication() {
    }

    public static void main(String[] args) {
        try {
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class);
            context.registerShutdownHook();
            context.getBean(DatabaseInitializer.class).initialize();
            context.getBean(HttpServer.class).start();
            context.getBean(LlmWorker.class).start();
            context.getBean(TelegramBotAdapter.class).start();
            log.info("Collaborative Novel Writing Bot started.");
        } catch (Exception ex) {
            log.error("Приложение не запущено: {}", ex.getMessage(), ex);
            System.err.println("Приложение не запущено: " + ex.getMessage());
            System.exit(1);
        }
    }
}
