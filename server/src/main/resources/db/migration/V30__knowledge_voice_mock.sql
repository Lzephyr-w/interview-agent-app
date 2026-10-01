ALTER TABLE ai_mock_interviews ADD COLUMN source_mode TEXT NOT NULL DEFAULT 'STANDARD';
ALTER TABLE ai_mock_interviews ADD COLUMN knowledge_document_ids TEXT NOT NULL DEFAULT '';
ALTER TABLE ai_mock_interview_questions ADD COLUMN knowledge_source TEXT NOT NULL DEFAULT '';
ALTER TABLE ai_mock_prepared_questions ADD COLUMN knowledge_source TEXT NOT NULL DEFAULT '';
