# Agent guide — BPMN Engine

This file orients AI agents (e.g. Cursor) working on this codebase.

## Project summary

- **What it is:** A BPMN 2.0 process execution engine: deploy BPMN XML, create instances, run service/user tasks and gateways.
- **Stack:** Spring Boot 4, Java 21, virtual threads. Optional PostgreSQL (persistence profile) and Kafka (opt-in). AI chat uses Gemini (API key from env).
- **Goals:** High throughput (target ≥100 completed instances/sec for 10-task chains), low latency, parse-once/execute-many. No JPA on the hot path; optional persistence for recovery and inspection.

## Repository and docs

- **Repo:** [github.com/bjck/BPMN-BKO](https://github.com/bjck/BPMN-BKO)
- **User-facing docs:** `README.md`
- **Cursor rules:** `.cursor/rules/*.mdc` — follow these when editing; they encode process-execution and performance constraints.

## Key code locations

| Area | Path | Notes |
|------|------|--------|
| REST API | `src/main/java/.../api/` | Controllers, DTOs, exception handling |
| Engine core | `src/main/java/.../engine/` | `ProcessEngine`, `TaskWorker`, `ConditionEvaluator`, events |
| Domain model | `src/main/java/.../model/` | `ProcessInstance`, `ProcessState` (sealed), `FlowNode`, `CompiledProcess` |
| BPMN parsing | `src/main/java/.../parser/` | `BpmnParser` — XML → `CompiledProcess` (DOM-based) |
| Persistence | `src/main/java/.../storage/` | JPA entities, repos; used only when `persistence` profile is active |
| Config | `src/main/java/.../config/` | `BpmnEngineConfig`, Kafka, Web |
| AI (Gemini) | `src/main/java/.../ai/` | `GeminiAiClient`, `AiAssistantService`, `AiChatController` |
| App config | `src/main/resources/application.yaml` | Virtual threads, Kafka, Gemini placeholders (no secrets) |
| Persistence config | `src/main/resources/application-persistence.yaml` | Datasource, JPA (profile: `persistence`) |
| BPMN editor (frontend) | `src/main/resources/static/js/bpmn/editor-app.js` | `BpmnEditorApp`: modeler init, properties panel (ID, name, gateway type dropdown, default flow, task config), gateway replace via `bpmnReplace`, custom `elements.move` rule so shapes can be moved without snap-back. Editor page: `pages/editor-page.js`. |
| E2E SPA (mocked API) | `e2e/spa.spec.js`, `e2e/fixtures/mock-data.js` | SPA tests mock all `/v1` API; list endpoint uses RegExp `/\/v1\/process-instances(\?|$)/` so `?page=1&size=20` is matched. No backend required. |

## Conventions (from `.cursor/rules`)

- **Process variables:** In-memory `ConcurrentHashMap` per instance; no disk reads/writes for active instances. Flush only at completion or checkpoint (e.g. UserTask reached).
- **Execution:** Sequential service-task chains run on the **same virtual thread** without yielding; no `@Async` on the hot path. Gateways resolved inline.
- **Workers:** Use virtual threads for I/O-bound workers; optimistic locking (version counter), not `synchronized`.
- **Parsing:** BPMN is parsed once at deploy time; runtime uses the compiled model only.
- **BPMN editor:** Properties panel includes a **Gateway type** dropdown (Exclusive, Parallel, Inclusive, Event-based, Complex); changing it replaces the gateway in place via `bpmnReplace.replaceElement`. A custom `additionalModules` rule allows `elements.move` so dragged elements do not snap back.

## Environment and secrets

- **Secrets are not in the repo.** API keys and env-specific values live in a root `.env` file (gitignored).
- The app loads `.env` via `spring.config.import: optional:file:.env[.properties]`. Run from project root so the file is found.
- **Gemini:** Set `GEMINI_API_KEY` in `.env` for AI chat; other Gemini options are optional (see README). Without the key, AI is disabled but the engine runs.

## Commands

```bash
# Build
mvn clean package

# Run (from project root)
mvn spring-boot:run
# Or: java -jar target/bpmn-engine-0.0.1-SNAPSHOT.jar

# Tests
mvn test

# E2E (start app first for full suite)
npm install && npx playwright install chromium && npm run test:e2e

# E2E SPA only (mocked API; no backend)
npm run test:e2e:spa

# JMH benchmarks
mvn package -DskipTests && java -jar target/jmh-benchmarks-jmh.jar
```

## Profiles

- **Default:** In-memory only; no DB required.
- **`persistence`:** Enables JPA and PostgreSQL (see `application-persistence.yaml` and README for Docker/DB setup).
- **`trace`:** Enables TRACE logging for engine, tasks, and Kafka (see `application-trace.yaml`). Use when debugging execution flow.

When suggesting config or code changes, preserve the above conventions and keep the hot path free of blocking I/O and `@Async`.
