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
  service     бизнес-логика, права доступа, LLM-запросы, админская статистика
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
| `HTTP_ADMIN_TOKEN` | Токен для данных и операций админ-панели через `/admin/api/*` |
| `HTTP_PORT` | HTTP-порт приложения, по умолчанию `8080` |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Подключение к PostgreSQL |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_QUEUE` | Подключение к RabbitMQ |
| `LLM_PROVIDER` | `GIGACHAT` или `OPENAI_COMPATIBLE`; по умолчанию `GIGACHAT` |
| `GIGACHAT_AUTH_KEY`, `GIGACHAT_SCOPE`, `GIGACHAT_OAUTH_URL` | OAuth-настройки GigaChat; ключ можно указывать как raw значение или с префиксом `Basic ` |
| `GIGACHAT_CA_CERT_PATH`, `GIGACHAT_VERIFY_SSL` | Опциональный путь к PEM/CRT сертификату Минцифры и флаг проверки SSL, по умолчанию `true` |
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
/request_status <request_id>
```

Обычная работа идет через inline-кнопки: `/novels` показывает список романов с пагинацией, карточка романа открывает главы, авторов, полный текст, LLM-действия и удаление романа. Карточка главы дает действия `Открыть редактор`, `Скачать .txt`, `Заменить текст`, `Добавить в конец`, `Переименовать`, `История`, `Удалить главу` и `LLM продолжить`.

Владелец романа может кнопками добавить владельца, добавить соавтора или удалить участника. Приглашаемый пользователь должен сначала запустить бота.

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

`GET /admin` отдает HTML-оболочку Web-панели администратора. Данные и операции панели выполняются через JSON endpoints `/admin/api/overview`, `/admin/api/users`, `/admin/api/novels`, `/admin/api/llm-requests`, которые доступны только с заголовком `X-Admin-Token`.

```bash
curl -H "X-Admin-Token: $HTTP_ADMIN_TOKEN" http://localhost:8080/admin/api/users
```

Администратор может вручную удалить произведение через панель или endpoint:

```bash
curl -X POST -H "X-Admin-Token: $HTTP_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Причина удаления"}' \
  http://localhost:8080/admin/api/novels/1/delete
```

Перед удалением бот отправляет всем владельцам и соавторам причину и полный `.txt` произведения. Если хотя бы одно уведомление не отправилось, произведение остается в базе.

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
- `POST /mini/api/chapters/{novelId}/{chapterId}/llm` — создать LLM-запрос из Web-редактора.
- `GET /mini/api/llm/{requestId}` — получить статус и результат LLM-запроса.

Mini App API требует заголовок `X-Telegram-Init-Data`; подпись проверяется по `TELEGRAM_BOT_TOKEN`, а `auth_date` должен быть не старше 24 часов.

## База данных

Минимальные таблицы:

- `app_users`: `chat_id`, `username`, `display_name`, `created_at`
- `novels`: название, описание, жанр, первичный владелец, даты
- `novel_authors`: роль `OWNER` или `CO_AUTHOR`
- `chapters`: заголовок, текст, порядок, последний редактор
- `chapter_history`: последние версии главы перед изменением
- `llm_requests`: тип запроса, статус `QUEUED`, `PROCESSING`, `DONE`, `ERROR`, provider/model, prompt, result/error, время завершения
- `telegram_sessions`: пошаговые сценарии Telegram, например создание романа и ожидание текста главы

Права доступа проверяются по `novel_authors`. У романа может быть несколько владельцев. Административная панель защищена отдельным HTTP-токеном и не зависит от Telegram-ролей.

## Тесты

```bash
gradle test
```

Покрыты регистрация пользователя, создание произведения, проверка доступа, дополнительные владельцы и соавторы, добавление главы, история изменений, статистика романа, optimistic conflict при сохранении главы, создание LLM-запроса со статусом `QUEUED`, Telegram callback-кнопки, WebUI-кнопка, Mini App LLM endpoints, `/healthcheck`, админский overview и админское удаление произведения с уведомлениями.

Дополнительные пояснения для защиты проекта собраны в `docs/ProjectDefenseGuide.md`.

## Ручная проверка

1. Запустите `docker compose up --build`.
2. Выполните в Telegram `/start` и `/help`.
3. Создайте произведение через `/new`.
4. Откройте `/novels`, выберите роман и добавьте главу кнопкой.
5. Проверьте карточку главы, скачивание `.txt`, замену текста и Mini App редактор, если задан `TELEGRAM_WEB_APP_URL`.
6. Вторым пользователем выполните `/start`, затем владельцем добавьте его через кнопку `Авторы`.
7. Проверьте, что третий пользователь без доступа не видит чужое произведение.
8. Выполните LLM-действия кнопками в карточке романа или главы.
9. Проверьте, что бот сам присылает результат или ошибку LLM; при необходимости проверьте `/request_status`.
10. Проверьте LLM-действия в Mini App редакторе.
11. Проверьте `GET /healthcheck`.
12. Проверьте `/admin`, `/admin/api/overview` и `/admin/api/users` без токена и с корректным `X-Admin-Token`.
13. Удалите тестовое произведение через админ-панель с причиной и проверьте, что авторы получили сообщение и `.txt`.

## Публикация

- Docker Hub: `https://hub.docker.com/r/<dockerhub-username>/novel-writing-bot`
- Telegram bot username: `@<your_bot_username>`
- GitHub: `https://github.com/<team>/<repository>`

Эти ссылки нужно заменить на реальные после публикации образа и создания Telegram-бота.
