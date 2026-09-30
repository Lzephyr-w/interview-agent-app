CREATE TABLE ai_mock_prepared_questions (
    ai_mock_interview_id TEXT NOT NULL REFERENCES ai_mock_interviews(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type TEXT NOT NULL,
    competency TEXT NOT NULL,
    project_name TEXT NOT NULL,
    technology TEXT NOT NULL,
    PRIMARY KEY (ai_mock_interview_id, sort_order)
);
