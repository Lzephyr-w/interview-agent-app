CREATE TABLE interview_audio_imports (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    object_path TEXT,
    status TEXT NOT NULL CHECK (status IN ('TRANSCRIBING', 'TRANSCRIPTION_FAILED', 'ANALYZING', 'ANALYSIS_FAILED', 'READY', 'SAVED')),
    transcript TEXT NOT NULL DEFAULT '',
    analysis_json TEXT,
    error TEXT NOT NULL DEFAULT '',
    final_interview_id TEXT REFERENCES interviews(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_interview_audio_imports_user ON interview_audio_imports(user_id, created_at DESC);
