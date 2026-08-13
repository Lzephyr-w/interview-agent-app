ALTER TABLE interview_packages
    ADD COLUMN resume_file_id TEXT REFERENCES resume_files(id) ON DELETE SET NULL;

CREATE INDEX idx_interview_packages_resume_file_id ON interview_packages(resume_file_id);
