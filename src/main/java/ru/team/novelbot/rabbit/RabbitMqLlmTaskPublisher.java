package ru.team.novelbot.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;

import java.nio.charset.StandardCharsets;

@Component
public class RabbitMqLlmTaskPublisher implements LlmTaskPublisher {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public RabbitMqLlmTaskPublisher(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(LlmTask task) {
        try {
            ConnectionFactory factory = connectionFactory();
            try (var connection = factory.newConnection();
                 var channel = connection.createChannel()) {
                channel.queueDeclare(properties.rabbit().queue(), true, false, false, null);
                byte[] body = objectMapper.writeValueAsString(task).getBytes(StandardCharsets.UTF_8);
                channel.basicPublish("", properties.rabbit().queue(), MessageProperties.PERSISTENT_TEXT_PLAIN, body);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось опубликовать LLM-задачу в RabbitMQ.", ex);
        }
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
}
