ALTER TABLE ai_mock_interviews ADD COLUMN material_snapshot TEXT;
ALTER TABLE mock_interviews ADD COLUMN material_snapshot TEXT;
ALTER TABLE ai_mock_interviews ADD COLUMN generation_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (generation_version IN ('LEGACY', 'SIMULATION_AGENT_V1'));
ALTER TABLE ai_mock_tasks ADD COLUMN available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_ai_mock_tasks_available ON ai_mock_tasks(status, available_at);
ALTER TABLE ai_mock_interview_questions ADD COLUMN ai_feedback TEXT NOT NULL DEFAULT '';
