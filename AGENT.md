# Agent Guide

## Project Summary

This repository is the MVP for **别错过大学 / Campus PO**, a campus information and social feed app.

The product centers on student and operator-created campus posts ("PO"), with recommendation tabs, school/college/major boards, reminders, comments, likes, favorites, follows, private messages, user profiles, level nameplates, and lightweight online presence.

The current UI is a Vue single-page app with an iOS-like bottom dock. The home screen has a left profile/recommendation rail, a central spotlight plus waterfall PO feed, and a right calendar/check-in rail. Message reminders include interaction tabs plus a dedicated private-message thread view.

## Tech Stack

- Frontend: Vue 3, Vite, TypeScript, Tailwind CSS, Pinia, Lucide icons, Axios, Day.js.
- Backend: Java 21, Spring Boot 3.3, Spring Security + JWT, MyBatis-Plus, Flyway.
- Local database: H2 under the `local` Spring profile.
- Production/deploy database: MySQL 8 via Docker Compose.
- Media storage: MinIO via Docker Compose.

## Repository Layout

- `frontend/src/App.vue`: Main SPA shell, views, most UI state, post interactions, messages, profiles, mock/fallback data.
- `frontend/src/styles.css`: Global styling, card layouts, level nameplates, message thread UI, responsive rules.
- `frontend/src/types.ts`: Shared frontend API/view types.
- `frontend/src/lib/api.ts`: Axios clients and frontend API wrappers.
- `backend/src/main/java/com/biecuoguo/domain`: MyBatis domain entities.
- `backend/src/main/java/com/biecuoguo/dto`: API DTO records.
- `backend/src/main/java/com/biecuoguo/mapper`: MyBatis-Plus mapper interfaces.
- `backend/src/main/java/com/biecuoguo/service`: Business logic.
- `backend/src/main/java/com/biecuoguo/web`: REST controllers.
- `backend/src/main/java/com/biecuoguo/security`: JWT auth, security config, token blacklist.
- `backend/src/main/resources/db/local`: H2/local Flyway migrations and seed data.
- `backend/src/main/resources/db/migration`: MySQL Flyway migrations and seed data.
- `docker-compose.yml`: MySQL, MinIO, backend, and frontend deployment stack.

## Local Development

Backend with H2:

```bash
cd backend
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local
```

Frontend:

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

Common URLs:

- Web: `http://localhost:5174/`
- API health: `http://localhost:8080/actuator/health`
- H2 console: `http://localhost:8080/h2-console`

Docker deployment:

```bash
cp .env.example .env
docker compose up -d --build
```

## Verification Commands

Use these before handing off code changes:

```bash
cd frontend
npm run build
```

```bash
cd backend
mvn test
```

`mvn package` may fail locally if the running backend is holding `target/biecuoguo-backend-0.1.0.jar`; prefer `mvn test` for compile/test verification unless packaging is specifically needed.

## Core Product Areas

### Posts And Boards

Posts are organized by board:

- `recommend`
- `school`
- `college`
- `major`

Frontend filtering and ranking live in `App.vue` around `filteredPosts`, `hotRankings`, `recommendScore`, and `hotScore`.

Important post interactions:

- `openPost(post)`: opens the post detail modal/panel.
- `jumpToPost(post)`: scroll-select behavior for the waterfall feed.
- Hot ranking rows should use `openPost(post)`, not just scroll to the card.
- `togglePostLike`, `togglePostFavorite`, `sharePost`, and comment helpers keep optimistic local UI state in sync with API calls when possible.

### Messages

Private messages are in:

- Backend: `MessageService`, `MessageController`, `Conversation`, `Message`, `MessageDtos`.
- Frontend: message view sections in `App.vue`, API wrappers in `frontend/src/lib/api.ts`.

Current behavior:

- Interaction tabs show likes, mentions, comments, and followers separately.
- Follower notifications are not shown as private messages.
- Private message cards open an in-page thread view.
- Thread body has fixed height and scrolls internally.
- Outgoing messages align right; incoming messages align left.
- Both sides show avatar, time, and outgoing read/unread state.
- Conversation lists and active threads poll for updates.

### Online Presence

Presence is lightweight and in-memory:

- Backend: `PresenceService`
- Auth heartbeat: `POST /api/auth/heartbeat`
- Login and `/auth/me` mark the current user online.
- Logout removes the user from presence.
- Users expire as offline after the configured in-memory online window.

Frontend sends heartbeat on login/session load and stops it on logout/unmount. Do not treat this as durable presence across backend restarts.

### Profiles And Level Nameplates

User level/nameplate data appears in posts, profile pages, side profile cards, and message headers.

Frontend nameplate styles are centralized in `frontend/src/styles.css`:

- `.level-nameplate`: base template.
- `.level-nameplate--compact`: compact post-card usage.
- `.level-nameplate--side`: left home profile card; currently placed under avatar/name row and full-width enough to avoid truncation.
- `.level-nameplate--profile`: profile header; scaled down from the base size.
- `.level-nameplate--chat`: private message header; compact/scaled down.
- `.level-nameplate--tier-*`: visual tiers.

When changing nameplate size, prefer class-specific scaling rather than changing `.level-nameplate` globally.

## API Map

Common endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/auth/heartbeat`
- `GET /api/auth/me`
- `GET /api/posts?tab=recommend|school|college|major`
- `POST /api/posts`
- `GET /api/posts/hot-today`
- `GET /api/posts/dont-miss`
- `GET /api/posts/{id}/comments`
- `POST /api/posts/{id}/comments`
- `POST /api/posts/{id}/like`
- `DELETE /api/posts/{id}/like`
- `POST /api/posts/{id}/favorite`
- `DELETE /api/posts/{id}/favorite`
- `POST /api/posts/{id}/share`
- `GET /api/users/me/stats`
- `GET /api/users/me/posts`
- `GET /api/users/me/likes`
- `GET /api/users/me/post-favorites`
- `GET /api/users/{uid}`
- `GET /api/users/{uid}/posts`
- `POST /api/users/{uid}/follow`
- `DELETE /api/users/{uid}/follow`
- `GET /api/messages/conversations`
- `POST /api/messages/conversations`
- `GET /api/messages/conversations/{id}/messages`
- `POST /api/messages/conversations/{id}/messages`
- `GET /api/messages/unread-count`
- `POST /api/media/upload`
- `PUT /api/admin/users/{id}/role`

## Design And UI Conventions

- Prefer using existing Vue state and CSS classes over introducing new frameworks.
- Use Lucide icons for buttons when an icon exists.
- Keep the first screen as the usable app, not a marketing landing page.
- Cards should stay restrained and functional. Avoid nested cards.
- Recent user preference: remove large decorative shadows behind the left rail, right rail, spotlight panel, and waterfall PO cards.
- Avoid one-hue decorative palettes and oversized hero typography inside compact UI surfaces.
- Keep text inside buttons/cards from overflowing; use stable dimensions and responsive max widths.
- For message UI, preserve right alignment for own messages and fixed-height internal scrolling.

## Backend Conventions

- Controllers return `ApiResponse<T>`.
- Business logic belongs in services; controllers should stay thin.
- DTOs are Java records under `dto`.
- Entities use MyBatis-Plus annotations and mappers.
- Schema changes require matching Flyway migrations for both:
  - `backend/src/main/resources/db/local`
  - `backend/src/main/resources/db/migration`
- Security uses JWT and `SecurityUtils.currentUser()`.
- Public GET endpoints are configured in `SecurityConfig`.

## Frontend Conventions

- `App.vue` is currently the main feature surface; keep edits localized and avoid broad rewrites unless deliberately refactoring.
- `types.ts` should mirror DTO shape for fields consumed by the frontend.
- `api.ts` should expose small, typed wrapper functions.
- For local optimistic updates, keep API fallback behavior graceful through the existing `safe()` helper.
- When touching message or profile UI, also check mobile rules near the bottom of `styles.css`.

## Known Quirks

- Some existing files and README text contain mojibake/encoding noise in Chinese strings. Do not mass-rewrite all copy unless that is the task; fix only the strings you are editing or the strings that block compilation.
- The backend jar can be locked by a running Spring Boot process, causing `mvn package` repackage rename failures.
- Presence is not persisted and is reset on backend restart.
- Frontend has fallback/mock data for offline or failed API states; verify both API-backed and fallback paths when changing shared UI.
- The in-app browser often runs on `http://localhost:5174/`, while README examples may mention `5173`.

## Suggested Handoff Checklist

- Read `frontend/src/App.vue` and `frontend/src/styles.css` before UI edits.
- Run `npm run build` after frontend changes.
- Run `mvn test` after backend changes.
- For backend DTO changes, update `frontend/src/types.ts` and `frontend/src/lib/api.ts` as needed.
- For database shape changes, update both local and production Flyway migration paths.
- If changing message behavior, test two logged-in accounts and verify conversation list polling, active thread polling, read state, and alignment.
- If changing profile/nameplates, check side card, profile page, post card, and message header separately.
