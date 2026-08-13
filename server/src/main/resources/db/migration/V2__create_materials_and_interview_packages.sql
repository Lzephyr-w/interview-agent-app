CREATE TABLE resumes (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE job_descriptions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    company TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE project_evidence_cards (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    project_name TEXT NOT NULL,
    background_and_role TEXT NOT NULL,
    goal_and_metrics TEXT NOT NULL,
    constraints_and_tradeoffs TEXT NOT NULL,
    personal_contribution TEXT NOT NULL,
    result_and_retrospective TEXT NOT NULL,
    applicable_question_types TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE interview_packages (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    company TEXT NOT NULL,
    role TEXT NOT NULL,
    interview_round TEXT NOT NULL,
    resume_id TEXT REFERENCES resumes(id) ON DELETE SET NULL,
    job_description_id TEXT REFERENCES job_descriptions(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE interview_package_evidence_cards (
    interview_package_id TEXT NOT NULL REFERENCES interview_packages(id) ON DELETE CASCADE,
    evidence_card_id TEXT NOT NULL REFERENCES project_evidence_cards(id) ON DELETE CASCADE,
    PRIMARY KEY (interview_package_id, evidence_card_id)
);

CREATE INDEX idx_resumes_user_id ON resumes(user_id);
CREATE INDEX idx_job_descriptions_user_id ON job_descriptions(user_id);
CREATE INDEX idx_project_evidence_cards_user_id ON project_evidence_cards(user_id);
CREATE INDEX idx_interview_packages_user_id ON interview_packages(user_id);
