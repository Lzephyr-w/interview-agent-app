-- V14 started timers for already-open questions. A timer must begin only after start-answer.
UPDATE ai_mock_interview_questions
SET answer_started_at = NULL,
    answer_expires_at = NULL
WHERE state = 'OPEN';
