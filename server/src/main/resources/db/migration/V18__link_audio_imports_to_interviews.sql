ALTER TABLE interview_audio_imports
    ADD COLUMN target_interview_id TEXT REFERENCES interviews(id);
