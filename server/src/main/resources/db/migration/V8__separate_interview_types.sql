ALTER TABLE interviews
    ADD COLUMN interview_type TEXT NOT NULL DEFAULT 'REAL'
    CHECK (interview_type IN ('REAL', 'MOCK'));

CREATE INDEX idx_interviews_user_type ON interviews(user_id, interview_type);
