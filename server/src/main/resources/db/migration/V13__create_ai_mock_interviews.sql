CREATE TABLE ai_mock_interviews (
    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, interview_package_id TEXT NOT NULL REFERENCES interview_packages(id) ON DELETE CASCADE,
    company TEXT NOT NULL, role TEXT NOT NULL, interview_round TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'TIME_EXPIRED', 'FINISHED', 'FAILED')),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL, finished_at TIMESTAMP WITH TIME ZONE,
    current_question_index INTEGER NOT NULL DEFAULT 0,
    final_interview_id TEXT REFERENCES interviews(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ai_mock_interview_questions (
    id TEXT PRIMARY KEY, ai_mock_interview_id TEXT NOT NULL REFERENCES ai_mock_interviews(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL, confirmed_answer_text TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'TRANSCRIBING', 'READY_TO_CONFIRM', 'ANSWERED', 'SKIPPED')),
    sort_order INTEGER NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ai_mock_audio_assets (
    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, ai_mock_interview_id TEXT NOT NULL REFERENCES ai_mock_interviews(id) ON DELETE CASCADE,
    question_id TEXT NOT NULL REFERENCES ai_mock_interview_questions(id) ON DELETE CASCADE,
    original_filename TEXT NOT NULL, content_type TEXT NOT NULL, size_bytes BIGINT NOT NULL, object_path TEXT NOT NULL,
    duration_ms BIGINT, status TEXT NOT NULL CHECK (status IN ('UPLOADED', 'TRANSCRIBING', 'READY', 'FAILED', 'DELETED')),
    transcript TEXT NOT NULL DEFAULT '', transcript_error TEXT NOT NULL DEFAULT '', feedback TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_ai_mock_interviews_user ON ai_mock_interviews(user_id, updated_at DESC);
CREATE INDEX idx_ai_mock_audio_question ON ai_mock_audio_assets(question_id, created_at DESC);
