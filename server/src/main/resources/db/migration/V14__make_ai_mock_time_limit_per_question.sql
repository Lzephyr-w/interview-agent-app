ALTER TABLE ai_mock_interview_questions
  ADD COLUMN answer_started_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE ai_mock_interview_questions
  ADD COLUMN answer_expires_at TIMESTAMP WITH TIME ZONE;

UPDATE ai_mock_interview_questions
SET answer_started_at = CURRENT_TIMESTAMP,
    answer_expires_at = CURRENT_TIMESTAMP + INTERVAL '5' MINUTE
WHERE state IN ('OPEN', 'TRANSCRIBING', 'READY_TO_CONFIRM');

UPDATE ai_mock_interviews SET status = 'RUNNING' WHERE status = 'TIME_EXPIRED';
