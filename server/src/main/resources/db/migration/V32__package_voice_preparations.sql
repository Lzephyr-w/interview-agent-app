CREATE TABLE ai_mock_package_preparations (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    interview_package_id TEXT NOT NULL REFERENCES interview_packages(id) ON DELETE CASCADE,
    material_fingerprint TEXT NOT NULL,
    generation_version TEXT NOT NULL,
    material_snapshot TEXT NOT NULL,
    generated_result TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (interview_package_id, material_fingerprint, generation_version)
);

ALTER TABLE ai_mock_interviews ADD COLUMN preparation_id TEXT
    REFERENCES ai_mock_package_preparations(id) ON DELETE SET NULL;
CREATE INDEX idx_ai_mock_interviews_preparation ON ai_mock_interviews(preparation_id);
