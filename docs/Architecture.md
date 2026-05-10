# Архитектура Collaborative Novel Writing Bot

## Общая структура

Приложение построено как серверное Java-приложение без Spring Boot. Spring Framework 7 используется через ручную Java-конфигурацию. Входные каналы разделены на Telegram long polling и WebFlux HTTP endpoints, бизнес-логика вынесена в сервисы, доступ к PostgreSQL выполняется через Spring JDBC.

Схема компонентов находится в файле [Архитектура.jpg](../Архитектура.jpg).

## Компоненты

- `TelegramBotAdapter` получает `message` и `callback_query` updates через Telegram Bot API и выполняет действия роутера: сообщения, редактирование, документы, callback-ответы.
- `CommandRouter` разбирает команды, callback-кнопки и пошаговые Telegram-сессии.
- `TelegramClient` инкапсулирует вызовы Telegram Bot API: `sendMessage`, `editMessageText`, `answerCallbackQuery`, `sendDocument`, `getFile`.
- `UserAuthService` регистрирует пользователя по `chat_id` и назначает `User` или `Admin`.
- `NovelService` управляет произведениями и авторами.
- `ChapterService` управляет главами, сохраняет историю изменений и поддерживает optimistic update для Mini App редактора.
- `AccessControlService` проверяет роли `OWNER` и `CO_AUTHOR`.
- `LlmRequestService` создает LLM-запрос со статусом `QUEUED`.
- `RabbitMqLlmTaskPublisher` публикует задачу в очередь `llm.requests`.
- `LlmWorker` читает задачи из RabbitMQ, вызывает LLM API, сохраняет `DONE` или `ERROR` и уведомляет пользователя в Telegram.
- `HttpRoutes` предоставляет `GET /healthcheck`, `GET /users` и Mini App endpoints редактора главы.
- `MiniAppAuthService` проверяет подпись Telegram `initData` для WebUI.
- Repository layer использует `JdbcTemplate` и SQL-запросы.

## Взаимодействие

Пользователь отправляет команду или нажимает inline-кнопку в Telegram. Адаптер превращает update в `TelegramInboundMessage`, затем `CommandRouter` вызывает нужный сервис и возвращает список Telegram-действий. Сервисы проверяют доступ и работают с репозиториями. Для LLM-действий сервис создает запись в `llm_requests`, публикует сообщение в RabbitMQ, а пользователь получает номер запроса. Worker асинхронно обрабатывает задачу, обновляет статус и отправляет результат или ошибку пользователю.

HTTP-клиент или браузер вызывает `/healthcheck` без авторизации. Для `/users` требуется заголовок `X-Admin-Token`; системная роль пользователя в Telegram для этого endpoint не используется, доступ контролируется HTTP-токеном. Mini App редактор вызывает `/mini/api/chapters/...` с заголовком `X-Telegram-Init-Data`; сервер проверяет подпись и затем применяет обычные права `OWNER`/`CO_AUTHOR`.

## Структура данных

```text
app_users(chat_id, username, display_name, role, created_at)
novels(id, title, description, genre, owner_chat_id, created_at, updated_at)
novel_authors(novel_id, chat_id, author_type)
chapters(id, novel_id, title, text, order_number, created_at, updated_at, last_editor_chat_id)
chapter_history(id, chapter_id, old_title, old_text, editor_chat_id, changed_at)
llm_requests(id, chat_id, novel_id, chapter_id, request_type, status, prompt, result, error_message, created_at, updated_at, provider, model, completed_at)
telegram_sessions(chat_id, state, novel_id, chapter_id, payload, updated_at)
```

`novel_authors.author_type` хранит `OWNER` или `CO_AUTHOR`. Удаление произведения каскадно удаляет авторов, главы, историю и LLM-запросы. История главы ограничивается последними пятью версиями.

## Обработка команд

Основной Telegram UX построен на `/new`, `/novels`, inline-кнопках и пошаговых сценариях. Старые ID-команды остаются совместимостью, но не нужны обычному пользователю. Нетекстовые сообщения принимаются в сценариях замены текста главы, если это `.txt` файл.

```text
Пожалуйста, используйте команды и кнопки. Введите /help для краткой справки.
```

## Запуск

Локальный запуск выполняется через fat JAR:

```bash
gradle clean shadowJar
java -jar build/libs/app.jar
```

Docker Compose запускает три сервиса: приложение, PostgreSQL и RabbitMQ. PostgreSQL использует volume `postgres_data`, поэтому данные сохраняются между перезапусками контейнера.
