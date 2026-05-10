# Collaborative Novel Writing Bot

Telegram-бот для совместного написания небольших литературных произведений. Пользователь создает произведение, добавляет главы, приглашает соавторов, смотрит полный текст и ставит LLM-задачи в очередь RabbitMQ.

## Команда

- Гвоздева Е.
- Крутиков Д.
- Михайлова А.
- Романова А.

## Технологии

- Java 21
- Gradle 9.4+
- Spring Framework 7 без Spring Boot
- Spring WebFlux
- Spring JDBC
- PostgreSQL
- RabbitMQ
- Spring REST Docs
- Docker Compose

## Структура

```text
src/main/java/ru/team/novelbot
  config      ручная Spring-конфигурация и параметры окружения
  db          инициализация SQL-схемы
  domain      сущности и enum-ы
  repository  Spring JDBC repositories
  service     бизнес-логика, права доступа, LLM-запросы
  telegram    Telegram long polling и роутер команд
  rabbit      RabbitMQ producer и LLM worker
  llm         HTTP-клиент LLM API
  http        WebFlux endpoints
src/main/resources/db/schema.sql
src/test/java  автотесты и Spring REST Docs
```

## Переменные окружения

Скопируйте `.env.example` в `.env` и заполните реальные секреты.

| Переменная | Назначение |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Токен Telegram-бота |
| `TELEGRAM_WEB_APP_URL` | HTTPS URL Mini App редактора главы, например `https://example.com/mini/chapter-editor`; если пустой, WebUI-кнопка скрыта |
| `ADMIN_CHAT_IDS` | Список chat_id администраторов через запятую |
| `HTTP_ADMIN_TOKEN` | Токен для `GET /users` |
| `HTTP_PORT` | HTTP-порт приложения, по умолчанию `8080` |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Подключение к PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_QUEUE` | Подключение к RabbitMQ |
| `LLM_PROVIDER` | `GIGACHAT` или `OPENAI_COMPATIBLE`; по умолчанию `GIGACHAT` |
| `GIGACHAT_AUTH_KEY`, `GIGACHAT_SCOPE`, `GIGACHAT_OAUTH_URL` | Авторизация GigaChat API для демонстрационного РФ-friendly режима |
| `LLM_API_KEY`, `LLM_API_URL`, `LLM_MODEL` | Настройки generic OpenAI-compatible endpoint; для GigaChat можно оставить пустыми, если используются дефолты |
| `PROJECT_AUTHORS` | Авторы для `/healthcheck` |

Секреты не должны попадать в репозиторий.

## Локальный запуск

Нужны Java 21, Gradle 9.4+, PostgreSQL и RabbitMQ.

```bash
gradle clean shadowJar
java -jar build/libs/app.jar
```

Приложение при старте создает таблицы из `src/main/resources/db/schema.sql`.

## Docker Compose

```bash
cp .env.example .env
# заполните .env
docker compose up --build
```

Compose поднимает приложение, PostgreSQL с volume `postgres_data` и RabbitMQ Management UI на `http://localhost:15672`.

## Telegram-команды и кнопки

```text
/start
/help
/new
/novels
/cancel
/delete_novel <novel_id>
/invite_author <novel_id> <chat_id>
/remove_author <novel_id> <chat_id>
/request_status <request_id>
```

Обычная работа идет через inline-кнопки: `/novels` показывает список романов с пагинацией, карточка романа открывает главы, карточка главы дает действия `Открыть редактор`, `Скачать .txt`, `Заменить текст`, `Добавить в конец`, `История` и `LLM продолжить`.

Старые команды с ID и синтаксисом через `|` оставлены как совместимость, но скрыты из основной справки.

Пример сценария:

```text
/start
/new
<бот пошагово спросит название, описание и жанр>
/novels
<выберите роман кнопкой>
<откройте главы и добавьте новую главу кнопкой>
```

## HTTP endpoints

`GET /healthcheck` доступен без авторизации.

```bash
curl http://localhost:8080/healthcheck
```

`GET /users` доступен только с заголовком `X-Admin-Token`.

```bash
curl -H "X-Admin-Token: $HTTP_ADMIN_TOKEN" http://localhost:8080/users
```

Документация HTTP API формируется тестами Spring REST Docs:

```bash
gradle asciidoctor
```

HTML будет создан в `build/docs/asciidoc/index.html`.

Mini App редактор главы:

- `GET /mini/chapter-editor` — HTML WebUI редактора.
- `GET /mini/api/chapters/{novelId}/{chapterId}` — загрузить главу.
- `PUT /mini/api/chapters/{novelId}/{chapterId}` — сохранить главу с optimistic check по `updated_at`.
- `GET /mini/api/chapters/{novelId}/{chapterId}/history` — последние версии главы.

Mini App API требует заголовок `X-Telegram-Init-Data`; подпись проверяется по `TELEGRAM_BOT_TOKEN`.

## База данных

Минимальные таблицы:

- `app_users`: `chat_id`, `username`, `display_name`, `role`, `created_at`
- `novels`: название, описание, жанр, владелец, даты
- `novel_authors`: роль `OWNER` или `CO_AUTHOR`
- `chapters`: заголовок, текст, порядок, последний редактор
- `chapter_history`: последние версии главы перед изменением
- `llm_requests`: тип запроса, статус `QUEUED`, `PROCESSING`, `DONE`, `ERROR`, provider/model, prompt, result/error, время завершения
- `telegram_sessions`: пошаговые сценарии Telegram, например создание романа и ожидание текста главы

Права доступа проверяются по `novel_authors`. Системная роль `Admin` дает доступ только к `/users`, но не открывает чужие произведения в Telegram.

## Тесты

```bash
gradle test
```

Покрыты регистрация пользователя, создание произведения, проверка доступа, добавление главы, история изменений, статистика романа, optimistic conflict при сохранении главы, создание LLM-запроса со статусом `QUEUED`, Telegram callback-кнопки, WebUI-кнопка, `/healthcheck`, `/users` и базовые Mini App endpoints.

## Ручная проверка

1. Запустите `docker compose up --build`.
2. Выполните в Telegram `/start` и `/help`.
3. Создайте произведение через `/new`.
4. Откройте `/novels`, выберите роман и добавьте главу кнопкой.
5. Проверьте карточку главы, скачивание `.txt`, замену текста и Mini App редактор, если задан `TELEGRAM_WEB_APP_URL`.
6. Вторым пользователем выполните `/start`, затем владельцем добавьте его через `/invite_author`.
7. Проверьте, что третий пользователь без доступа не видит чужое произведение.
8. Выполните LLM-действия кнопками в карточке романа или главы.
9. Проверьте, что бот сам присылает результат или ошибку LLM; при необходимости проверьте `/request_status`.
10. Проверьте `GET /healthcheck`.
11. Проверьте `GET /users` без токена и с корректным `X-Admin-Token`.

## Публикация

- Docker Hub: `https://hub.docker.com/r/<dockerhub-username>/novel-writing-bot`
- Telegram bot username: `@<your_bot_username>`
- GitHub: `https://github.com/<team>/<repository>`

Эти ссылки нужно заменить на реальные после публикации образа и создания Telegram-бота.
