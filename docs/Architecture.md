# Архитектура Collaborative Novel Writing Bot

## Общая структура

Приложение построено как серверное Java-приложение без Spring Boot. Spring Framework 7 используется через ручную Java-конфигурацию. Входные каналы разделены на Telegram long polling и WebFlux HTTP endpoints, бизнес-логика вынесена в сервисы, доступ к PostgreSQL выполняется через Spring JDBC.

Схема компонентов находится в файле [Архитектура.jpg](../Архитектура.jpg).

## Компоненты

- `TelegramBotAdapter` получает `message` и `callback_query` updates через Telegram Bot API и выполняет действия роутера: сообщения, редактирование, документы, callback-ответы.
- `CommandRouter` разбирает команды, callback-кнопки и пошаговые Telegram-сессии.
- `TelegramClient` инкапсулирует вызовы Telegram Bot API: `sendMessage`, `editMessageText`, `answerCallbackQuery`, `sendDocument`, `getFile`.
- `UserAuthService` регистрирует пользователя по `chat_id`, обновляет профиль Telegram и назначает системную роль `USER` или `ADMIN`.
- `NovelService` управляет произведениями, владельцами и соавторами.
- `ChapterService` управляет главами, сохраняет историю изменений и поддерживает optimistic update для Mini App редактора.
- `AccessControlService` проверяет роли `OWNER` и `CO_AUTHOR`.
- `LlmRequestService` создает LLM-запрос со статусом `QUEUED`.
- `AdminStatsService` собирает статистику для админ-панели.
- `RabbitMqLlmTaskPublisher` публикует задачу в очередь `llm.requests`.
- `LlmWorker` читает задачи из RabbitMQ, вызывает LLM API, сохраняет `DONE` или `ERROR` и уведомляет пользователя в Telegram.
- `HttpLlmClient` поддерживает GigaChat OAuth + Bearer, OpenAI-compatible endpoints, кэширование access token и опциональный сертификат GigaChat через `GIGACHAT_CA_CERT_PATH`.
- `HttpRoutes` предоставляет `GET /healthcheck`, Web-админку, Admin JSON API, Mini App endpoints редактора главы и Mini App LLM endpoints.
- `MiniAppAuthService` проверяет подпись Telegram `initData` для WebUI.
- Repository layer использует `JdbcTemplate` и SQL-запросы.

## Взаимодействие

Пользователь отправляет команду или нажимает inline-кнопку в Telegram. Адаптер превращает update в `TelegramInboundMessage`, затем `CommandRouter` вызывает нужный сервис и возвращает список Telegram-действий. Сервисы проверяют доступ и работают с репозиториями. Для LLM-действий сервис создает запись в `llm_requests`, публикует сообщение в RabbitMQ, а пользователь получает номер запроса. Worker асинхронно обрабатывает задачу, обновляет статус и отправляет результат или ошибку пользователю.

HTTP-клиент или браузер вызывает `/healthcheck` без авторизации. Для `/admin/api/*` требуется `X-Admin-Token` либо `X-Telegram-Init-Data` от Telegram Mini App пользователя с ролью `ADMIN`; список пользователей в админке показывает системную роль из `app_users.role` и позволяет назначать `ADMIN`/`USER` без перезапуска приложения. Mini App редактор вызывает `/mini/api/chapters/...` и `/mini/api/llm/...` с заголовком `X-Telegram-Init-Data`; сервер проверяет подпись, свежесть `auth_date` и затем применяет обычные права `OWNER`/`CO_AUTHOR`.

## Структура данных

```text
app_users(chat_id, username, display_name, role, created_at)
novels(id, title, description, genre, owner_chat_id, created_at, updated_at)
novel_authors(novel_id, chat_id, author_type)
chapters(id, novel_id, title, text, order_number, created_at, updated_at, created_by_chat_id, last_editor_chat_id)
chapter_history(id, chapter_id, old_title, old_text, editor_chat_id, changed_at)
llm_requests(id, chat_id, novel_id, chapter_id, request_type, status, prompt, result, error_message, created_at, updated_at, provider, model, completed_at)
telegram_sessions(chat_id, state, novel_id, chapter_id, payload, updated_at)
```

`novel_authors.author_type` хранит `OWNER` или `CO_AUTHOR`. У произведения может быть несколько владельцев, последний владелец не удаляется. Обычное удаление владельцем и админское удаление каскадно удаляют авторов, главы, историю и LLM-запросы. При админском удалении `AdminNovelDeletionService` сначала отправляет всем авторам причину и полный `.txt`; если хотя бы одно Telegram-уведомление не прошло, удаление блокируется. `chapter_history` хранит старые версии перед изменениями, а текущая версия остается в `chapters`.

## Обработка команд

Основной Telegram UX построен на `/new`, `/novels`, `/request_status`, inline-кнопках и пошаговых сценариях. Управление авторами, главами, полным текстом, LLM и удалением выполняется кнопками. Нетекстовые сообщения принимаются в сценариях замены текста главы, если это `.txt` файл.

```text
Пожалуйста, используйте команды и кнопки. Введите /help для краткой справки.
```

## Запуск

Локальный запуск выполняется через fat JAR:

```bash
gradle clean shadowJar
java -jar build/libs/app.jar
```

Docker Compose запускает три сервиса: само приложение, PostgreSQL и RabbitMQ. PostgreSQL использует volume `postgres_data`, поэтому данные не пропадают при перезапуске контейнера.
