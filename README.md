# BPMN Engine

A high-performance BPMN 2.0 process execution engine built with Spring Boot 4 and Java 21. The engine parses BPMN XML at deploy time, compiles processes to an internal model, and executes flow nodes synchronously on virtual threads—targeting **100 completed process instances per second**, each with 10 sequential service tasks.

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

### Running with PostgreSQL

**Docker Compose (full stack):**
```bash
docker compose -f docker/docker-compose.yml up -d
# App: http://localhost:8080, PostgreSQL: localhost:5432
```

**Local development (DB only, run app from IDE):**
```bash
docker compose -f docker/docker-compose.dev.yml up -d
# Then run the app with: spring.profiles.active=persistence
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
| `GET`  | `/v1/process-instances/{id}` | Get instance (active or completed) |
| `POST` | `/v1/process-instances/{id}/complete-task/{taskId}` | Complete user task; body: `{ variables? }` |
| `DELETE` | `/v1/process-instances/{id}` | Cancel running instance |
| `GET`  | `/v1/health` | Health check; returns `UP`, active instance count, deployed process count |
| `GET`  | `/v1/process-instances/{id}/history` | Process audit events and task executions (persistence profile only) |
| `GET`  | `/v1/processes/{processDefinitionId}/bpmn` | BPMN 2.0 XML for the deployed process (for viewer) |

---

## BPMN Viewer (Frontend)

The web UI includes a BPMN viewer: select a process instance and click **View BPMN** to see the diagram with the current state (current node highlighted, completed nodes in green). Click diagram elements to see variables at that point. BPMN files must include **Diagram Interchange (bpmndi)** for the viewer to render; the samples under `src/main/resources/static/samples/` include bpmndi.

### Frontend E2E Tests

E2E tests verify the BPMN visualization using Playwright. **Start the app first**, then run:

```bash
npm install
npx playwright install chromium
npm run test:e2e
```

Optional: `BASE_URL=http://localhost:8080 npm run test:e2e` (default is `http://localhost:8080`).

---

## Configuration

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

- `startEvent`, `endEvent`
- `serviceTask` (with `implementation` attribute)
- `userTask` (with optional `camunda:assignee`)
- `exclusiveGateway` (with `default` flow and `conditionExpression` on sequence flows)
- `parallelGateway` (fork and join)
- `sequenceFlow` (with optional `conditionExpression`)

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

```bash
# Build
mvn clean package

# Run
java -jar target/bpmn-engine-0.0.1-SNAPSHOT.jar

# Or with Maven
mvn spring-boot:run
```

The application starts on port 8080 (default). Use the REST API to deploy processes and create instances.

---

## Dependencies

- **Spring Boot 4.0.3** (Web MVC, Actuator)
- **Java 21**
- **JMH 1.37** (benchmarks)

No JAXB or third-party BPMN libraries—parsing uses `javax.xml.parsers.DocumentBuilder` (JDK). Condition evaluation uses Spring's SpEL (included via Spring Boot).

---

## License

See project metadata in `pom.xml`.
