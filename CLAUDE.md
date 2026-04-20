# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Tasker is an AI-powered weekly planner: a task backlog plus a Telegram-driven weekly planning conversation that reads Google Calendar and writes agreed tasks back as time blocks. The product vision (core loop, task fields, integrations, non-goals) lives in `docs/SPEC.md` — read it before making design decisions. `docs/SPEC - Deprecated.md` is superseded; don't use it.

The repo is early-stage scaffolding: backend is an empty Spring Boot application, frontend is a stock Vite+React starter. Most features in the spec are not yet implemented — expect to be building from scratch rather than extending existing modules.

## Repo layout

- `src/` — Kotlin/Spring Boot backend (`dev.itayp.tasker`). Entry point: `src/main/kotlin/dev/itayp/tasker/TaskerApplication.kt`.
- `tasker-frontend/` — separate React + TypeScript + Vite app. Not wired into the Gradle build; runs independently.
- `compose.yaml` — Postgres service for local dev. Spring Boot's `spring-boot-docker-compose` dev dependency starts it automatically when running the app locally.
- `docs/SPEC.md` — product spec (source of truth for intent).

## Common commands

Backend (run from repo root):
- `./gradlew bootRun` — run the Spring Boot app (auto-starts Postgres via compose).
- `./gradlew build` — compile + test.
- `./gradlew test` — run all tests.
- `./gradlew test --tests "dev.itayp.tasker.SomeTest.someMethod"` — run a single test.

Frontend (run from `tasker-frontend/`):
- `npm run dev` — Vite dev server with HMR.
- `npm run build` — `tsc -b && vite build`.
- `npm run lint` — ESLint.

## Stack notes that affect how you write code

- **JVM 25** is configured via Gradle toolchain (`build.gradle.kts`). `HELP.md` mentions a downgrade to 24 was once needed for Kotlin compat — if you hit a Kotlin/JVM version mismatch, check Kotlin 2.3.20's supported JVM targets before changing Java versions.
- **Kotlin Spring plugins active**: `kotlin-spring` (auto-opens Spring-managed classes) and `kotlin-jpa` with `allOpen` for `@Entity`, `@MappedSuperclass`, `@Embeddable`. You do not need to mark JPA entities `open` manually.
- **Jackson**: uses `tools.jackson.module:jackson-module-kotlin` (Jackson 3.x, `tools.jackson` package), not the older `com.fasterxml.jackson.*`. Import accordingly.
- **Spring Boot 4.0.x / LLM**: `spring-ai-spring-boot-docker-compose` is on the dev classpath but Spring AI is **not** the chosen approach. Use a thin, hand-rolled abstraction over the Claude API (Anthropic SDK or raw HTTP) instead — the planner doesn't need Spring AI's higher-level abstractions.
- **Databases**: Postgres in dev/prod, H2 available (likely for tests). `application.yaml` currently has no datasource config — Spring Boot auto-config + docker-compose handle it locally.
- **Security starter** is included but no config is written yet; any new endpoint will hit Spring Security defaults until explicitly configured.
- **Compiler flags**: `-Xjsr305=strict` (treat JSR-305 nullability as errors) and `-Xannotation-default-target=param-property` (Kotlin 2.x annotation target default). Keep nullability annotations honest.

## Frontend specifics

- React 19, Vite 8, TypeScript ~6.0. The app is a fresh template — `src/App.tsx` is the default Vite boilerplate. No routing, state management, or API client exists yet; pick them deliberately when first needed.
- Frontend and backend are independent builds today. There is no proxy or shared build wiring — if you add API calls, you'll need to set up CORS or a Vite dev proxy.
