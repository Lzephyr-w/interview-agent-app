ALTER TABLE interview_questions ADD COLUMN ai_feedback TEXT NOT NULL DEFAULT '';

UPDATE interview_questions iq
SET ai_feedback = (
    SELECT mq.ai_feedback
    FROM mock_interviews mi
    JOIN mock_interview_questions mq ON mq.mock_interview_id = mi.id
    WHERE mi.finished_interview_id = iq.interview_id
      AND mq.sort_order = iq.sort_order
)
WHERE EXISTS (
    SELECT 1
    FROM mock_interviews mi
    JOIN mock_interview_questions mq ON mq.mock_interview_id = mi.id
    WHERE mi.finished_interview_id = iq.interview_id
      AND mq.sort_order = iq.sort_order
      AND mq.ai_feedback <> ''
);
