-- Create history table with same structure as process_instance (active table).
-- Then move existing COMPLETED/FAILED rows from process_instance into history.

CREATE TABLE IF NOT EXISTS process_instance_history (
    instance_id UUID NOT NULL PRIMARY KEY,
    process_definition_id VARCHAR(255) NOT NULL,
    state VARCHAR(32) NOT NULL,
    current_node_id VARCHAR(255),
    error_message TEXT,
    variables_json TEXT,
    parallel_join_tokens_json TEXT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_process_instance_history_completed_at ON process_instance_history (completed_at);
CREATE INDEX IF NOT EXISTS idx_process_instance_history_definition ON process_instance_history (process_definition_id);

-- Migrate COMPLETED/FAILED rows from active table to history (only if process_instance table exists, e.g. upgrade from single-table).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'process_instance') THEN
    INSERT INTO process_instance_history (
        instance_id, process_definition_id, state, current_node_id, error_message,
        variables_json, parallel_join_tokens_json, created_at, completed_at, version
    )
    SELECT instance_id, process_definition_id, state, current_node_id, error_message,
           variables_json, parallel_join_tokens_json, created_at, completed_at, version
    FROM process_instance
    WHERE state IN ('COMPLETED', 'FAILED')
    ON CONFLICT (instance_id) DO NOTHING;

    DELETE FROM process_instance WHERE state IN ('COMPLETED', 'FAILED');
  END IF;
END $$;
