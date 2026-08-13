CREATE TABLE mock_interviews (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    interview_package_id TEXT NOT NULL REFERENCES interview_packages(id),
    company TEXT NOT NULL,
    role TEXT NOT NULL,
    interview_round TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'FINISHED')),
    total_questions INTEGER NOT NULL,
    current_question_index INTEGER NOT NULL DEFAULT 0,
    finished_interview_id TEXT REFERENCES interviews(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE mock_interview_questions (
    id TEXT PRIMARY KEY,
    mock_interview_id TEXT NOT NULL REFERENCES mock_interviews(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    answer_text TEXT NOT NULL DEFAULT '',
    self_assessment TEXT NOT NULL DEFAULT 'UNCERTAIN',
    question_kind TEXT NOT NULL CHECK (question_kind IN ('MAIN', 'FOLLOW_UP')),
    parent_question_id TEXT REFERENCES mock_interview_questions(id),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'OPEN', 'ANSWERED', 'SKIPPED')),
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_mock_interviews_user_id ON mock_interviews(user_id);
CREATE INDEX idx_mock_interview_questions_session ON mock_interview_questions(mock_interview_id, sort_order);
