ALTER TABLE resume_files
  ADD COLUMN parsed_text TEXT;

ALTER TABLE resume_files
  ADD COLUMN parsed_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE resume_files
  ADD COLUMN parsed_truncated BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE resume_files
  ADD COLUMN parsed_error TEXT;

ALTER TABLE resume_files
  ADD COLUMN parsed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE resume_files
  ADD CONSTRAINT chk_resume_files_parsed_status
  CHECK (parsed_status IN ('PENDING', 'READY', 'FAILED'));
