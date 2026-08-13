CREATE TABLE training_tasks (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    weakness_tag TEXT NOT NULL,
    action TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    source_interview_id TEXT REFERENCES interviews(id) ON DELETE SET NULL,
    source_review_report_id TEXT REFERENCES review_reports(id) ON DELETE SET NULL
);

CREATE INDEX idx_training_tasks_user_id ON training_tasks(user_id);
CREATE INDEX idx_training_tasks_source_interview_id ON training_tasks(source_interview_id);
CREATE INDEX idx_training_tasks_source_review_report_id ON training_tasks(source_review_report_id);
