CREATE TABLE knowledge_categories (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, name)
);

CREATE TABLE knowledge_documents (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    category_id TEXT NOT NULL REFERENCES knowledge_categories(id),
    original_filename TEXT NOT NULL,
    format TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    object_path TEXT NOT NULL,
    segment_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_knowledge_documents_user_category ON knowledge_documents(user_id, category_id);

CREATE TABLE knowledge_segments (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    kind TEXT NOT NULL,
    location TEXT NOT NULL,
    content TEXT NOT NULL,
    original_answer TEXT NOT NULL DEFAULT '',
    reference_answer TEXT NOT NULL DEFAULT '',
    reminder TEXT NOT NULL DEFAULT ''
);
CREATE INDEX idx_knowledge_segments_document ON knowledge_segments(document_id, position);

ALTER TABLE mock_interviews ADD COLUMN source_mode TEXT NOT NULL DEFAULT 'STANDARD';
ALTER TABLE mock_interviews ADD COLUMN knowledge_document_ids TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN source_document_id TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN source_segment_id TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN source_title TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN source_location TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN source_text TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN reference_answer TEXT;
ALTER TABLE mock_interview_questions ADD COLUMN source_reminder TEXT;
