# Архитектура Collaborative Novel Writing Bot

## Общая структура

Приложение построено как серверное Java-приложение без Spring Boot. Spring Framework 7 используется через ручную Java-конфигурацию. Входные каналы разделены на Telegram long polling и WebFlux HTTP endpoints, бизнес-логика вынесена в сервисы, доступ к PostgreSQL выполняется через Spring JDBC.

Схема компонентов находится в файле [Архитектура.jpg](../Архитектура.jpg).

## Компоненты

- `TelegramBotAdapter` получает updates через Telegram Bot API и отправляет ответы.
- `CommandRouter` разбирает команды и аргументы, выполняет первичную валидацию.
- `UserAuthService` регистрирует пользователя по `chat_id` и назначает `User` или `Admin`.
- `NovelService` управляет произведениями и авторами.
- `ChapterService` управляет главами и сохраняет историю изменений.
- `AccessControlService` проверяет роли `OWNER` и `CO_AUTHOR`.
- `LlmRequestService` создает LLM-запрос со статусом `QUEUED`.
- `RabbitMqLlmTaskPublisher` публикует задачу в очередь `llm.requests`.
- `LlmWorker` читает задачи из RabbitMQ, вызывает LLM API и сохраняет `DONE` или `ERROR`.
- `HttpRoutes` предоставляет `GET /healthcheck` и `GET /users`.
- Repository layer использует `JdbcTemplate` и SQL-запросы.

## Взаимодействие

Пользователь отправляет команду в Telegram. Адаптер превращает update в `TelegramInboundMessage`, затем `CommandRouter` вызывает нужный сервис. Сервисы проверяют доступ и работают с репозиториями. Для LLM-команд сервис создает запись в `llm_requests`, публикует сообщение в RabbitMQ, а пользователь получает номер запроса. Worker асинхронно обрабатывает задачу и обновляет статус.

HTTP-клиент или браузер вызывает `/healthcheck` без авторизации. Для `/users` требуется заголовок `X-Admin-Token`; системная роль пользователя в Telegram для этого endpoint не используется, доступ контролируется HTTP-токеном.

## Структура данных

```text
app_users(chat_id, username, display_name, role, created_at)
novels(id, title, description, genre, owner_chat_id, created_at, updated_at)
novel_authors(novel_id, chat_id, author_type)
chapters(id, novel_id, title, text, order_number, created_at, updated_at, last_editor_chat_id)
chapter_history(id, chapter_id, old_title, old_text, editor_chat_id, changed_at)
llm_requests(id, chat_id, novel_id, chapter_id, request_type, status, prompt, result, error_message, created_at, updated_at)
```

`novel_authors.author_type` хранит `OWNER` или `CO_AUTHOR`. Удаление произведения каскадно удаляет авторов, главы, историю и LLM-запросы. История главы ограничивается последними пятью версиями.

## Обработка команд

Все Telegram-ответы написаны на русском языке. Если аргументов недостаточно или ID передан в неверном формате, бот возвращает пример корректной команды. Нетекстовые сообщения получают ответ:

```text
Пожалуйста, используйте текстовые команды. Введите /help для списка команд.
```

## Запуск

Локальный запуск выполняется через fat JAR:

```bash
gradle clean shadowJar
java -jar build/libs/app.jar
```

Docker Compose запускает три сервиса: приложение, PostgreSQL и RabbitMQ. PostgreSQL использует volume `postgres_data`, поэтому данные сохраняются между перезапусками контейнера.
