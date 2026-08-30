CREATE TABLE weakness_analyses (
    user_id TEXT PRIMARY KEY,
    input_fingerprint TEXT NOT NULL,
    summary TEXT NOT NULL,
    items_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE training_tasks
    ADD COLUMN source_question_id TEXT REFERENCES interview_questions(id) ON DELETE SET NULL;

CREATE INDEX idx_training_tasks_source_question_id ON training_tasks(source_question_id);
