CREATE TABLE ai_mock_tasks (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    task_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    related_id TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    worker_token TEXT,
    locked_at TIMESTAMP WITH TIME ZONE,
    error TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_type, resource_id, related_id)
);

CREATE INDEX idx_ai_mock_tasks_claim ON ai_mock_tasks(status, locked_at, created_at);
CREATE INDEX idx_ai_mock_tasks_user_resource ON ai_mock_tasks(user_id, resource_id, created_at DESC);
