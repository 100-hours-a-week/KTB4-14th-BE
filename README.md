# AUDIGO-BE

AUDIGO backend scaffold.

## Stack

- Java 21
- Spring Boot 4.0.6
- Gradle
- MySQL 8.4
- Redis 7.4 (prepared for shared chat presence)

## Domain Package Rule

- `domain.auth`: login, token, OAuth provider integration
- `domain.auth.oauth`: Kakao OAuth client details
- `domain.user`: user profile and my page
- `domain.notification`: notification list and settings
- `global`: cross-cutting config, security, response, errors

`OAuth` is an authentication method, not the whole domain name. Keep the domain as `auth` and put Kakao-specific code under `auth/oauth`.

## Local Configuration

Set these environment variables before running against real Kakao/MySQL.

```properties
DB_USERNAME=
DB_PASSWORD=
AUDIGO_JWT_SECRET=
AUDIGO_CORS_ALLOWED_ORIGINS=http://localhost:5173
KAKAO_REST_API_KEY=
KAKAO_CLIENT_SECRET=
KAKAO_REDIRECT_URI=http://localhost:5173/auth/kakao
```

## Local MySQL and Redis

```shell
docker compose up -d mysql redis
docker compose exec redis redis-cli ping
```

The Redis check should return `PONG`. The backend connects to `localhost:6379` by default; set `AUDIGO_REDIS_HOST` and `AUDIGO_REDIS_PORT` if the shared Redis is elsewhere. Connection and read timeouts default to 2 seconds and can be changed with `AUDIGO_REDIS_CONNECT_TIMEOUT` and `AUDIGO_REDIS_TIMEOUT`.

## Run

```shell
gradle bootRun
```

If Gradle is not installed on PATH, run `bootRun` from the IntelliJ Gradle tool window after reimporting the project.

Refresh token은 Redis의 `auth:refresh_token:{sessionId}` 키에 14일 TTL로 저장합니다. Redis 값에는 사용자 ID와 토큰 비밀 값의 해시가 들어가며, 재발급 시 비밀 값과 TTL을 교체합니다. 기존 MySQL `refresh_tokens` 기록은 읽지 않으므로 전환 후 기존 사용자는 다시 로그인해야 합니다. Access token은 서명된 JWT로 유지됩니다.

If Hibernate already created old development tables before the ERD column names were aligned, recreate the local schema once:

```sql
DROP DATABASE audigo;
CREATE DATABASE audigo
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

## Kakao Login Development Order

1. Create the `audigo` schema in MySQL.
2. Set `DB_USERNAME`, `DB_PASSWORD`, `KAKAO_REST_API_KEY`, `KAKAO_REDIRECT_URI`, and `AUDIGO_JWT_SECRET` in the IDE run configuration.
3. Register `http://localhost:5173/auth/kakao` in Kakao Developers as a redirect URI.
4. Start `AUDIGO-BE`.
5. Set `VITE_USE_MOCK=false`, `VITE_API_BASE_URL=http://localhost:8080`, `VITE_KAKAO_REST_KEY`, and `VITE_KAKAO_REDIRECT_URI=http://localhost:5173/auth/kakao` in `AUDIGO-FE`.
6. Start the React app and click Kakao login.
