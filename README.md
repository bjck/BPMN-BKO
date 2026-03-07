# BPMN Engine

A high-performance BPMN 2.0 process execution engine built with Spring Boot 4 and Java 21. The engine parses BPMN XML at deploy time, compiles processes to an internal model, and executes flow nodes synchronously on virtual threads—targeting **100 completed process instances per second**, each with 10 sequential service tasks.

**Repository:** [github.com/bjck/BPMN-BKO](https://github.com/bjck/BPMN-BKO)

## Overview

This engine implements a subset of BPMN 2.0 for process orchestration:

- **Deploy** BPMN 2.0 XML processes via REST API
- **Create** process instances with initial variables
- **Execute** service tasks, user tasks, gateways, and events
- **Complete** user tasks asynchronously via API calls

The design prioritizes throughput and low latency by:

- Parsing BPMN XML **once at deploy time** (never at runtime)
- Executing sequential service task chains **synchronously on the same virtual thread** (no yielding, no re-queuing)
- Using **Java 21 virtual threads** for scalable concurrency
- Storing process instances in **memory** (`ConcurrentHashMap`) with no JPA on the hot path
- **Optional persistence** to PostgreSQL for disaster recovery and inspection (activate with `persistence` profile)

---

## Persistence (Disaster Recovery)

When the `persistence` profile is active, the engine persists process state at checkpoint boundaries:

| Checkpoint | When |
|------------|------|
| Instance created | Before execution starts |
| UserTask reached | When process waits for human input |
| Instance completed | When process reaches End Event |
| UserTask completed | After variables merged, before advancing |

**Guarantee:** Synchronous writes ensure no data loss on crash. Persistence is off the hot path—sequential service chains run entirely in memory.

### Running with PostgreSQL and Kafka

**Docker Compose (full stack: DB + Kafka + Kafka UI + app):**
```bash
docker compose -f docker/docker-compose.yml up -d
# App: http://localhost:8080 | PostgreSQL: localhost:5432 | Kafka: localhost:9092 | Kafka UI: http://localhost:8091
```
The app runs with `persistence` and Kafka enabled (`BPMN_KAFKA_ENABLED=true`).

**Local development (DB + Kafka + Kafka UI; run app from IDE):**
```bash
docker compose -f docker/docker-compose.dev.yml up -d
# PostgreSQL: localhost:5432 | Kafka: localhost:9092 | Kafka UI: http://localhost:8091
# Run the app with: spring.profiles.active=persistence
# Optional: BPMN_KAFKA_ENABLED=true to enable BPMN message/signal events and Kafka service tasks
```

### Inspection API

When persistence is active, inspect process history:

```
GET /v1/process-instances/{instanceId}/history
```

Returns audit events (CREATED, USER_TASK_REACHED, COMPLETED) and task execution records with timing.

---

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           REST API (ProcessController)                    │
│  POST /v1/processes  │  POST /v1/process-instances  │  GET /v1/health    │
└─────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            ProcessEngine                                 │
│  • deployedProcesses: Map<definitionId, CompiledProcess>                 │
│  • activeInstances: Map<instanceId, ProcessInstance>                     │
│  • completedInstances: Map<instanceId, ProcessInstance>                  │
│  • workers: Map<implementation, TaskWorker>                              │
└─────────────────────────────────────────────────────────────────────────┘
         │                    │                      │
         ▼                    ▼                      ▼
┌──────────────┐    ┌─────────────────┐    ┌──────────────────┐
│  BpmnParser  │    │  TaskWorker      │    │  ConditionEvaluator│
│  (parse once)│    │  (registered)   │    │  (SpEL)           │
└──────────────┘    └─────────────────┘    └──────────────────┘
```

### Process Lifecycle (FSM)

Process instances follow a finite state machine with a **sealed interface** and exhaustive pattern matching:

```
    Created ──► Active ──► Completing ──► Completed
                   │
                   └──► Failed
```

| State       | Description                                      |
|-------------|--------------------------------------------------|
| `Created`   | Instance created, not yet started                |
| `Active`    | Running; holds current node ID                   |
| `Completing`| Reached End Event, transitioning to Completed    |
| `Completed` | Process finished successfully                   |
| `Failed`    | Process failed (reserved for future use)         |

State transitions use Java 21 pattern-matching `switch`—exhaustive, no `default` fallback.

### Flow Node Types

| Node Type        | Behavior                                                                 |
|------------------|--------------------------------------------------------------------------|
| **StartEvent**   | Triggers execution; advances to first outgoing flow                      |
| **EndEvent**     | Marks instance as Completing → Completed; publishes completion event     |
| **ServiceTask**  | Invokes registered `TaskWorker` by implementation; merges result vars    |
| **UserTask**     | Blocks until `completeTask()` is called via API                          |
| **ExclusiveGateway** | Evaluates SpEL conditions on outgoing flows; selects one branch     |
| **ParallelGateway**  | Fork: spawns parallel branches; Join: waits for all incoming tokens  |

### Sequential Chain Optimization

Consecutive `ServiceTask` nodes with no gateways between them form a **sequential chain**. The engine detects these at compile time and executes the entire chain on the **same virtual thread** without yielding—avoiding context switches and maximizing throughput for linear workflows.

---

## Project Structure

```
src/
├── main/java/com/bko/bpmn_engine/
│   ├── api/                    # REST controllers, DTOs, exception handling
│   │   ├── ProcessController   # Deploy, create instance, complete task, cancel
│   │   ├── HealthController    # /v1/health with active instance count
│   │   ├── ApiExceptionHandler
│   │   └── dto/                # Request/response records
│   ├── config/
│   │   └── BpmnEngineConfig    # Registers default "java" TaskWorker
│   ├── engine/
│   │   ├── ProcessEngine       # Core execution logic
│   │   ├── TaskWorker          # Functional interface for service tasks
│   │   ├── ConditionEvaluator  # SpEL expression evaluation
│   │   └── event/              # Spring ApplicationEvents (TaskActivated, etc.)
│   ├── model/                  # Domain model
│   │   ├── ProcessInstance     # instanceId, variables, state, timestamps
│   │   ├── ProcessState        # Sealed: Created, Active, Completing, Completed, Failed
│   │   ├── FlowNode            # Sealed: StartEvent, EndEvent, ServiceTask, UserTask, gateways
│   │   ├── ProcessDefinition   # nodes, sequenceFlows, startNodeId, endNodeIds
│   │   ├── CompiledProcess     # definition + adjacency + sequentialChains
│   │   └── SequenceFlow        # sourceRef, targetRef, conditionExpression
│   └── parser/
│       ├── BpmnParser          # BPMN 2.0 XML → CompiledProcess (DOM-based)
│       └── BpmnParseException
│   └── storage/                # Persistence (persistence profile only)
│       ├── entity/             # JPA entities
│       ├── repository/         # Spring Data JPA repositories
│       ├── JpaProcessInstanceStorage
│       └── ProcessRecoveryRunner  # Restores state on startup
├── test/
│   ├── java/                   # Unit tests, LoadTest
│   └── resources/fixtures/     # BPMN samples (sequential, exclusive, parallel)
└── jmh/java/                   # JMH benchmarks (ProcessEngineBenchmark)
```

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/processes` | Deploy BPMN XML; returns `processDefinitionId` |
| `GET`  | `/v1/processes` | List deployed process definition IDs |
| `POST` | `/v1/process-instances` | Create instance; body: `{ processDefinitionId, variables? }` |
| `POST` | `/v1/process-instances/message-start` | Start instance by message (message start event); body: `{ processDefinitionId, messageRef, correlationKey?, variables? }` |
| `POST` | `/v1/process-instances/{id}/trigger-catch` | Trigger intermediate catch event by node id; body: `{ nodeId, variables? }` |
| `POST` | `/v1/bpmn-events/trigger-catch` | Trigger catch event by messageRef; body: `{ messageRef, correlationKey?, variables? }` |
| `GET`  | `/v1/process-instances/{id}` | Get instance (active or completed) |
| `POST` | `/v1/process-instances/{id}/complete-task/{taskId}` | Complete user task; body: `{ variables? }` |
| `DELETE` | `/v1/process-instances/{id}` | Cancel running instance |
| `GET`  | `/v1/health` | Health check; returns `UP`, active instance count, deployed process count |
| `GET`  | `/v1/process-instances/{id}/history` | Process audit events and task executions (persistence profile only) |
| `GET`  | `/v1/processes/{processDefinitionId}/bpmn` | BPMN 2.0 XML for the deployed process (for viewer) |

---

## BPMN Viewer and Editor (Frontend)

### BPMN Viewer

The web UI includes a BPMN viewer: select a process instance and click **View BPMN** to see the diagram with the current state (current node highlighted, completed nodes in green). Click diagram elements to see variables at that point. BPMN files must include **Diagram Interchange (bpmndi)** for the viewer to render; the samples under `src/main/resources/static/samples/` include bpmndi.

### BPMN Editor

The **Editor** tab provides a full BPMN modeler (bpmn.io) for designing diagrams:

- **Palette and context pad** — Create and connect elements (start/end events, tasks, gateways) from the left palette or from the context pad on a selected element.
- **Properties panel** — When you select an element, the right-hand panel shows ID, name, and type-specific options. For **gateways**, a **Gateway type** dropdown lets you switch between Exclusive (XOR), Parallel (AND), Inclusive (OR), Event-based, and Complex without redrawing; the element is replaced in place and connections are preserved.
- **Element movement** — Shapes can be dragged to reposition; a custom rules module ensures moves are allowed and not reverted (no snap-back).
- **Deploy** — Use **Deploy process** to push the current diagram to the engine; **Copy XML** copies the BPMN 2.0 XML to the clipboard.

### Frontend E2E Tests

E2E tests verify the BPMN visualization and process execution using Playwright. **Start the app first**, then run:

```bash
npm install
npx playwright install chromium
npm run test:e2e
```

- **`e2e/bpmn-viewer.spec.js`** — Viewer displays diagram for a selected instance (deploy counting process, create instance, open instances page).
- **`e2e/spa.spec.js`** — SPA navigation and AI chat with mocked API (no backend required).
- **`e2e/bpmn-diagrams.spec.js`** — Full BPMN execution against live backend: minimal process, user task (complete-task), exclusive and parallel gateways, **message start event**, **intermediate catch event** (trigger by nodeId or messageRef), **intermediate throw event**, and **Kafka service task** (topic, messageMapping, keyMapping, resultVariable). Requires backend; Kafka test is skipped if Kafka is not enabled.

Optional: `BASE_URL=http://localhost:8080 npm run test:e2e` (default is `http://localhost:8080`). For the Kafka service task test, run with Kafka (e.g. `docker compose -f docker/docker-compose.dev.yml up -d`) and `BPMN_KAFKA_ENABLED=true`.

---

## Configuration

### Environment variables (`.env`)

Secrets and environment-specific settings are **not** committed. Create a `.env` file in the project root (the file is gitignored). The app loads it via `spring.config.import: optional:file:.env[.properties]`.

| Variable | Description | Default |
|----------|-------------|---------|
| `GEMINI_API_KEY` | Google Gemini API key for AI chat in the web UI | *(required for AI)* |
| `GEMINI_MODEL` | Gemini model name | `gemini-3.1-pro-preview` |
| `GEMINI_BASE_URL` | Gemini API base URL | `https://generativelanguage.googleapis.com` |
| `GEMINI_TIMEOUT` | Request timeout | `60s` |
| `BPMN_KAFKA_ENABLED` | Enable Kafka (BPMN events + Kafka service tasks) | `false` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker(s); use when not default | `localhost:9092` |
| `BPMN_KAFKA_TOPIC` | Kafka topic for BPMN message/signal events | `bpmn-events` |
| `BPMN_KAFKA_CONSUMER_GROUP` | Consumer group for BPMN event listener | `bpmn-engine` |

Example `.env` (only set what you need):

```properties
GEMINI_API_KEY=your-gemini-api-key
# Optional: enable Kafka (e.g. with docker compose -f docker/docker-compose.dev.yml up -d)
# BPMN_KAFKA_ENABLED=true
# SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

Without `GEMINI_API_KEY`, the AI chat in the UI will report that the provider is not configured; the rest of the engine runs normally. With Kafka disabled (default), Kafka service tasks and BPMN message/signal over Kafka are unavailable; the broker URL is only used when `BPMN_KAFKA_ENABLED=true`.

### Virtual Threads

Virtual threads are enabled globally in `application.yaml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### Task Workers

Service tasks reference an `implementation` (e.g. `"java"`). Workers are registered with the engine:

```java
engine.registerWorker("java", vars -> Map.of("result", "done"));
```

The default configuration registers a no-op worker for `"java"` in `BpmnEngineConfig`.

---

## BPMN Support

### Supported Elements

| Element | Notes |
|--------|--------|
| **Events** | |
| `startEvent` | Optional `engine:messageRef` (message start), `engine:timerDefinition` (timer start). One per process. |
| `endEvent` | Optional `engine:messageRef`, `engine:errorCode`. |
| `intermediateCatchEvent` | Optional `engine:messageRef` (message catch), `engine:timerDefinition` (timer). Trigger via API by node id or messageRef. |
| `intermediateThrowEvent` | Optional `engine:messageRef`, `engine:signalRef`. Publishes to in-memory event bus; with Kafka enabled, to Kafka. |
| **Tasks** | |
| `serviceTask` | `implementation` attribute (e.g. `java`). Extension: REST, bean, or Kafka task via `engine:taskConfiguration`. |
| `userTask` | Optional `camunda:assignee`. Blocks until `complete-task` API call. |
| **Gateways** | |
| `exclusiveGateway` | XOR. Optional `default` flow. Outgoing flows may have `conditionExpression` (SpEL/FEEL). |
| `parallelGateway` | AND fork/join. No conditions. |
| `inclusiveGateway` | OR. Optional `default` flow. Conditions on outgoing flows. |
| `eventBasedGateway` | Optional `default` flow. Followed by event-based branches. |
| `complexGateway` | Optional `default` flow; `engine:activationExpression` and `engine:activationLanguage` (e.g. FEEL). |
| **Connections** | |
| `sequenceFlow` | Optional `conditionExpression` (SpEL or FEEL) and `name`. |

### Condition Expressions

Conditions use **Spring Expression Language (SpEL)**. Supported format: `${expression}` or bare expression.

Example: `${flag == true}` evaluates against process variables.

---

## Performance

### Targets

- **Throughput:** ≥ 100 completed process instances per second (10 sequential tasks each)
- **Latency:** TP99 < 50 ms for 10-task sequential chain
- **CI gate:** 6,000 instances complete in under 5 minutes

### JVM Tuning

Recommended for benchmarks and production:

```
-XX:+UseZGC -XX:MaxGCPauseMillis=1
```

### Benchmarks

JMH benchmarks are in `src/jmh/java`. Run with:

```bash
mvn package -DskipTests
java -jar target/jmh-benchmarks-jmh.jar
```

### Load Test

A simple load test (`LoadTest` in `src/test/java`) runs 10,000 instances with virtual threads and reports PI/s. Run the `LoadTest` main class from your IDE, or use the Maven exec plugin with `exec.classpathScope=test`.

---

## Events

The engine publishes Spring `ApplicationEvent`s for observability:

| Event | When |
|-------|------|
| `ProcessInstanceCreatedEvent` | Instance created |
| `TaskActivatedEvent` | Service task started |
| `TaskCompletedEvent` | Service task finished |
| `ProcessInstanceCompletedEvent` | Instance reached End Event |

Listeners can subscribe via `@EventListener` for metrics, logging, or integration.

---

## Building and Running

**Quick start:**

1. Clone the repo and create a `.env` in the project root if you use AI chat (see [Environment variables](#environment-variables-env)).
2. Build and run:

```bash
# Build
mvn clean package

# Run
java -jar target/bpmn-engine-0.0.1-SNAPSHOT.jar

# Or with Maven
mvn spring-boot:run
```

The application starts on port 8080 (default). Use the REST API to deploy processes and create instances. Start from the project root so the optional `.env` file is found.

---

## Dependencies

- **Spring Boot 4.0.3** (Web MVC, Actuator)
- **Java 21**
- **JMH 1.37** (benchmarks)

No JAXB or third-party BPMN libraries—parsing uses `javax.xml.parsers.DocumentBuilder` (JDK). Condition evaluation uses Spring's SpEL (included via Spring Boot).

---

## License

See project metadata in `pom.xml`.
