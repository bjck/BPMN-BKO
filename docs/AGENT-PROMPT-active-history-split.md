# Agent prompt: Active vs History table split

**Implement the active/history table split for process instances as specified.**

Read and follow the full specification:

- **`docs/spec-active-history-split.md`**

In short:

1. **Storage:** Add `process_instance_history` (same columns as `process_instance`). Keep `process_instance` for active only (CREATED, ACTIVE, COMPLETING). In `JpaProcessInstanceStorage.save()`: write COMPLETED/FAILED to history and delete from active; write other states to active only. `findById` → active first, then history. `findAllActive()` → active table only.
2. **REST:** Add optional `page`/`size` to `GET /v1/process-instances`; return one page (e.g. active + one page of history by `completed_at` desc). Keep `GET /v1/process-instances/{id}` and `GET .../history` unchanged.
3. **Frontend:** Update instances list to use `?page=&size=` and server-driven pagination so running and completed/failed instances still show correctly.
4. **Migration:** Add a DB migration that creates `process_instance_history` and moves existing COMPLETED/FAILED rows from `process_instance` into it.

Implement all file changes and tests called out in the spec. Preserve existing REST contracts where the spec says “no change.”
