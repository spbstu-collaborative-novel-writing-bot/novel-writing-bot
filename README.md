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
| `ADMIN_CHAT_IDS` | Список chat_id администраторов через запятую |
| `HTTP_ADMIN_TOKEN` | Токен для `GET /users` |
| `HTTP_PORT` | HTTP-порт приложения, по умолчанию `8080` |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Подключение к PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_QUEUE` | Подключение к RabbitMQ |
| `LLM_API_KEY` | Ключ LLM API; если пустой, LLM-команды недоступны |
| `LLM_API_URL` | OpenAI-compatible endpoint |
| `LLM_MODEL` | Имя модели |
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

## Telegram-команды

```text
/start
/help
/new_novel <название> | <описание> | <жанр>
/my_novels
/novel <novel_id>
/delete_novel <novel_id>
/invite_author <novel_id> <chat_id>
/remove_author <novel_id> <chat_id>
/authors <novel_id>
/add_chapter <novel_id> | <название главы> | <текст главы>
/chapters <novel_id>
/chapter <novel_id> <chapter_id>
/full_text <novel_id>
/update_chapter <novel_id> <chapter_id> | <новое название> | <новый текст>
/delete_chapter <novel_id> <chapter_id>
/chapter_history <novel_id> <chapter_id>
/continue_chapter <novel_id> <chapter_id>
/advice <novel_id> <вопрос>
/draft <novel_id> <запрос>
/request_status <request_id>
```

Пример сценария:

```text
/start
/new_novel Звезды над городом | История о будущем мегаполисе | фантастика
/my_novels
/add_chapter 1 | Пролог | Ночь над городом была слишком тихой.
/chapter 1 1
/continue_chapter 1 1
/request_status 1
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

## База данных

Минимальные таблицы:

- `app_users`: `chat_id`, `username`, `display_name`, `role`, `created_at`
- `novels`: название, описание, жанр, владелец, даты
- `novel_authors`: роль `OWNER` или `CO_AUTHOR`
- `chapters`: заголовок, текст, порядок, последний редактор
- `chapter_history`: последние версии главы перед изменением
- `llm_requests`: тип запроса, статус `QUEUED`, `PROCESSING`, `DONE`, `ERROR`, prompt, result/error

Права доступа проверяются по `novel_authors`. Системная роль `Admin` дает доступ только к `/users`, но не открывает чужие произведения в Telegram.

## Тесты

```bash
gradle test
```

Покрыты регистрация пользователя, создание произведения, проверка доступа, добавление главы, история изменений, создание LLM-запроса со статусом `QUEUED`, а также `/healthcheck` и `/users` для REST Docs.

## Ручная проверка

1. Запустите `docker compose up --build`.
2. Выполните в Telegram `/start` и `/help`.
3. Создайте произведение через `/new_novel`.
4. Добавьте главу через `/add_chapter`.
5. Посмотрите главу через `/chapter` и полный текст через `/full_text`.
6. Вторым пользователем выполните `/start`, затем владельцем добавьте его через `/invite_author`.
7. Проверьте, что третий пользователь без доступа не видит чужое произведение.
8. Выполните `/continue_chapter`, `/advice` или `/draft`.
9. Проверьте статус через `/request_status`.
10. Проверьте `GET /healthcheck`.
11. Проверьте `GET /users` без токена и с корректным `X-Admin-Token`.

## Публикация

- Docker Hub: `https://hub.docker.com/r/<dockerhub-username>/novel-writing-bot`
- Telegram bot username: `@<your_bot_username>`
- GitHub: `https://github.com/<team>/<repository>`

Эти ссылки нужно заменить на реальные после публикации образа и создания Telegram-бота.
