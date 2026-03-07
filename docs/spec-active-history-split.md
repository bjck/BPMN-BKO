# Spec: Active vs History Table Split for Process Instances

## Goal

Split process instance storage into **active** (running) and **history** (completed/failed) tables so that:

- Recovery and checkpoint writes hit a small active table (better performance).
- Completed/failed instances are moved to history for long-term storage.
- The REST API and frontend still show both running and completed/failed instances (list, detail, history).

---

## 1. Data model

### 1.1 Tables

- **`process_instance` (active)**  
  - Same columns as today’s `process_instance`: `instance_id` (PK), `process_definition_id`, `state`, `current_node_id`, `error_message`, `variables_json`, `parallel_join_tokens_json`, `created_at`, `completed_at`, `version`.  
  - Only rows with `state` in (`CREATED`, `ACTIVE`, `COMPLETING`).  
  - Row is **removed** when instance reaches `COMPLETED` or `FAILED` (moved to history).

- **`process_instance_history`**  
  - Same columns as active table (or a copy of the same entity shape).  
  - Only rows with `state` in (`COMPLETED`, `FAILED`).  
  - Long-term store; no deletion by the application (retention can be handled later by a separate job if needed).

- **`process_instance_event`** and **`task_execution`**  
  - Keep as today: append-only, keyed by `instance_id`.  
  - No split in this spec; they are queried by `instance_id` for the history API. Optional: later add archival/partitioning by time.

### 1.2 Entity and repository layout

- **Option A (recommended):**  
  - One entity class (e.g. `ProcessInstanceEntity`) mapped to **two tables** via JPA `@Table(name = "process_instance")` for the active table and a second entity (e.g. `ProcessInstanceHistoryEntity`) with `@Table(name = "process_instance_history")` for history, same columns.  
  - Two repositories: `ProcessInstanceRepository` (active), `ProcessInstanceHistoryRepository` (history).

- **Option B:**  
  - Single entity, two tables with a shared superclass or MappedSuperclass for the common fields.

Use the same column types and indexes as today on both tables (e.g. index on `state` for active if still used; history can have index on `completed_at` for listing).

---

## 2. Storage interface and implementation

**File:** `src/main/java/com/bko/bpmn_engine/storage/ProcessInstanceStorage.java`

- **No signature changes** to the public interface: `save`, `saveEvent`, `saveTaskExecutions`, `findAllActive`, `findById`, `findAll` keep the same method signatures.

**File:** `src/main/java/com/bko/bpmn_engine/storage/JpaProcessInstanceStorage.java`

- **`findAllActive()`**  
  - Query **only** the active table (e.g. `ProcessInstanceRepository` / active table).  
  - No `state` filter needed if the table only ever has CREATED/ACTIVE/COMPLETING (or keep `findByStateIn` for safety).

- **`findById(UUID instanceId)`**  
  - Look up in **active** table first.  
  - If not found, look up in **history** table.  
  - Return `RecoveredInstance` from whichever table has the row (same mapping as today).

- **`save(ProcessInstance instance, ...)`**  
  - If `instance.state()` is **CREATED, ACTIVE, or COMPLETING**:  
    - Upsert into the **active** table only (findById on active, then save; same as today’s logic, but only on active table).  
  - If `instance.state()` is **COMPLETED or FAILED**:  
    - **Insert** into **history** table (full row).  
    - **Delete** the row from the **active** table (if present).  
    - Optionally: in the same transaction, still append to `process_instance_event` / `task_execution` if the consumer sends events/task records for the final checkpoint.

- **`saveEvent`** and **`saveTaskExecutions`**  
  - Unchanged: still write to the existing `process_instance_event` and `task_execution` tables (by `instance_id`). No table split for these in this spec.

Add a **`ProcessInstanceHistoryRepository`** (and, if used, `ProcessInstanceHistoryEntity`) with at least:

- `save(ProcessInstanceHistoryEntity)` (or equivalent).  
- `findById(UUID instanceId)` for history.  
- Optional: `findAllOrderByCompletedAtDesc(Pageable pageable)` or similar for paginated list.

---

## 3. Checkpoint consumer (Kafka → DB)

**File:** `src/main/java/com/bko/bpmn_engine/storage/CheckpointEventConsumer.java`

- Consumer logic stays the same: for each checkpoint payload, call `instanceStorage.save(...)`, `saveEvent(...)`, `saveTaskExecutions(...)`.  
- No change to the consumer’s **code**; the behavioral change is inside `JpaProcessInstanceStorage.save()` (active vs history + delete from active) as above.

So: when the consumer receives a checkpoint with state COMPLETED or FAILED, the storage implementation writes the row to history and removes it from active.

---

## 4. Recovery

**File:** `src/main/java/com/bko/bpmn_engine/storage/ProcessRecoveryRunner.java`

- **No change.** It already uses `instanceStorage.findAllActive()`.  
- `findAllActive()` now reads only from the active table, which is the intended behavior.

---

## 5. ProcessEngine

**File:** `src/main/java/com/bko/bpmn_engine/engine/ProcessEngine.java`

- **`getInstance(UUID instanceId)`**  
  - Already uses `instanceStorage.findById(instanceId)`.  
  - No change; `findById` now resolves from active then history.

- **`getAllInstances()`**  
  - Today: `instanceStorage.findAll()`.  
  - **New contract for storage:** `findAll()` may be **paginated** (see below).  
  - Either:  
  - Keep `findAll()` returning a single list (e.g. “active + first N history rows”) for backward compatibility, **or**  
  - Add a new method on storage (e.g. `findAll(Pageable)` or `findAll(int limit, int offset)`) and use it from the API layer.  
  - The spec below assumes the REST API will support pagination and the engine will call a paginated storage API (or a capped `findAll()`).

---

## 6. REST API changes

**File:** `src/main/java/com/bko/bpmn_engine/api/ProcessController.java`

- **`GET /v1/process-instances` (list)**  
  - **Current:** Returns all instances in one response; frontend does client-side pagination (PAGE_SIZE 10).  
  - **New:**  
    - Support **optional** query params, e.g. `page` (1-based) and `size` (page size, default e.g. 50, max e.g. 100).  
    - Backend returns a **page** of instances: e.g. **all active** (small set) **plus** a page of **history** ordered by `completed_at` desc (or `created_at` desc).  
    - Response shape can stay the same: `ListInstancesResponse(instances)` where `instances` is the current page.  
    - Add **total count** or **hasMore** only if needed; minimal change is to return one page and let the frontend use “Next” for the next page.  
  - Implementation: either  
  - `ProcessInstanceStorage.findAll(int page, int size)` returning e.g. “active list + one page of history”, or  
  - `ProcessInstanceStorage.findActive()` + `ProcessInstanceStorage.findHistoryPage(int page, int size)`, and the controller merges/sorts (e.g. active first, then history page) and returns one list for that page.  
  - **Backward compatibility:** If `page`/`size` are omitted, return a default page (e.g. page 1, size 50) so existing clients still get a list (possibly truncated) without changes.

- **`GET /v1/process-instances/{instanceId}` (detail)**  
  - No change. Still uses `processEngine.getInstance(instanceId)`.  
  - Resolution from active then history is handled inside storage.

- **`GET /v1/process-instances/{instanceId}/history`**  
  - No change. Still uses event and task_execution tables by `instance_id`.

Other endpoints (create, complete-task, cancel, restart, etc.) are unchanged.

---

## 7. Frontend (instances page)

**File:** `src/main/resources/static/js/pages/instances-page.js`

- **List loading:**  
  - Today: `GET /v1/process-instances` → `allInstances = response.instances`, then client-side pagination (PAGE_SIZE 10).  
  - **New:**  
    - Call `GET /v1/process-instances?page=1&size=50` (or whatever default size).  
    - Use the returned `instances` as the current page (or as the full list for that page).  
    - If the API returns a **total** or **hasMore**, use it to show “Page X of Y” or “Load more” / “Next page” by requesting the next `page`.  
  - Goal: frontend still shows both **running** and **completed/failed** instances; running instances should appear (e.g. first) and completed/failed in subsequent pages or merged in the same response, depending on how you design the single-page response (active first + one page of history).

- **Detail and history:**  
  - No change: still `GET /v1/process-instances/{id}` and `GET /v1/process-instances/{id}/history`.  
  - Ensure that when the user clicks a completed/failed instance (from the list), the detail and history still load; that only requires `findById` to resolve from history when not in active (already specified above).

---

## 8. DTOs and response shape

**File:** `src/main/java/com/bko/bpmn_engine/api/dto/ListInstancesResponse.java`

- Keep `ListInstancesResponse(List<InstanceSummary> instances)`.  
- Optionally add fields for pagination, e.g. `totalCount`, `page`, `pageSize`, `hasMore` (if you want the frontend to show total count or “next page” without guessing).

---

## 9. Migration and schema

- **Schema:**  
  - Rename current `process_instance` table to `process_instance_active` (or create new `process_instance_active` and migrate data), and create `process_instance_history` with the same column layout.  
  - Or: create new `process_instance_history` and keep the current table as the **active** table; then **migrate** existing rows with `state` in (`COMPLETED`, `FAILED`) from the current table into `process_instance_history` and delete them from the active table.  
  - Ensure JPA entity/table names match (e.g. `@Table(name = "process_instance")` for active if you keep the name, or `process_instance_active`).

- **Migration script (e.g. Flyway/Liquibase or plain SQL):**  
  - Create `process_instance_history` if it doesn’t exist (same columns as current `process_instance`).  
  - If starting from a single table: copy rows with `state IN ('COMPLETED','FAILED')` into `process_instance_history`, then delete those rows from the active table.  
  - No change to `process_instance_event` or `task_execution` in this spec.

---

## 10. Files to touch (summary)

| Area | File(s) |
|------|--------|
| Entities | `storage/entity/ProcessInstanceEntity.java` (active table); **new** `ProcessInstanceHistoryEntity.java` (or re-use one entity with two table names if preferred). |
| Repositories | `storage/repository/ProcessInstanceRepository.java` (active only); **new** `ProcessInstanceHistoryRepository.java` (findById, save, optional findPage). |
| Storage interface | `storage/ProcessInstanceStorage.java` (optional: add `findHistoryPage(page, size)` or keep only `findAll()` with internal pagination). |
| Storage implementation | `storage/JpaProcessInstanceStorage.java` (findAllActive → active table; findById → active then history; save → active vs history + delete from active). |
| Checkpoint consumer | `storage/CheckpointEventConsumer.java` (no logic change; relies on new save() behavior). |
| Recovery | `storage/ProcessRecoveryRunner.java` (no change). |
| Engine | `engine/ProcessEngine.java` (getInstance/getAllInstances: use storage as above; if you add paginated findAll, call it from controller). |
| REST API | `api/ProcessController.java` (list: add query params, call storage for one page or active+history page). |
| DTOs | `api/dto/ListInstancesResponse.java` (optional pagination fields). |
| Frontend | `static/js/pages/instances-page.js` (list: use ?page=&size=, handle pagination from response). |
| Schema / migration | New migration script (e.g. under `src/main/resources/db/migration` or similar) to create history table and migrate COMPLETED/FAILED rows. |

---

## 11. Testing

- **Unit:** `JpaProcessInstanceStorage` (or equivalent): save COMPLETED → row in history and not in active; findById finds it from history; findAllActive returns only active rows.  
- **Integration:** Run a process to completion with persistence + checkpoint consumer; assert one row in history and none in active for that instance; call GET list and GET by id and assert the instance appears.  
- **Recovery:** Start with only active rows; run recovery; assert only those are loaded.  
- **Frontend:** Manually or E2E: list shows running and completed; clicking a completed instance shows detail and history.

---

## 12. Optional follow-ups (out of scope for this spec)

- Retention/archival for `process_instance_history` (e.g. delete or archive rows older than N days).  
- Partitioning or archival of `process_instance_event` / `task_execution` by time.  
- Indexes on history (e.g. `completed_at`, `process_definition_id`) for analytics or listing.
