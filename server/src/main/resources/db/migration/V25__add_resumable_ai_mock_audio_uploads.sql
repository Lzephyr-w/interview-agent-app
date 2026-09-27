CREATE TABLE ai_mock_audio_uploads (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    ai_mock_interview_id TEXT NOT NULL REFERENCES ai_mock_interviews(id) ON DELETE CASCADE,
    question_id TEXT NOT NULL REFERENCES ai_mock_interview_questions(id) ON DELETE CASCADE,
    content_type TEXT NOT NULL,
    total_bytes BIGINT NOT NULL,
    total_parts INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('UPLOADING', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_asset_id TEXT REFERENCES ai_mock_audio_assets(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ai_mock_audio_upload_parts (
    upload_id TEXT NOT NULL REFERENCES ai_mock_audio_uploads(id) ON DELETE CASCADE,
    part_no INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    object_path TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (upload_id, part_no)
);
CREATE INDEX idx_ai_mock_audio_upload_active ON ai_mock_audio_uploads(question_id, status, updated_at DESC);
CREATE INDEX idx_ai_mock_audio_upload_expiry ON ai_mock_audio_uploads(status, expires_at);
