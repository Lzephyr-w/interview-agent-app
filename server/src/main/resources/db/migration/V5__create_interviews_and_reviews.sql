CREATE TABLE interviews (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    interview_package_id TEXT NOT NULL REFERENCES interview_packages(id),
    company TEXT NOT NULL,
    role TEXT NOT NULL,
    interview_round TEXT NOT NULL,
    interview_time TIMESTAMP WITH TIME ZONE NOT NULL,
    status TEXT NOT NULL,
    result TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    transcript TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE interview_questions (
    id TEXT PRIMARY KEY,
    interview_id TEXT NOT NULL REFERENCES interviews(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    answer_text TEXT NOT NULL,
    self_assessment TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_reports (
    id TEXT PRIMARY KEY,
    interview_id TEXT NOT NULL REFERENCES interviews(id) ON DELETE CASCADE,
    readiness TEXT NOT NULL,
    summary TEXT NOT NULL,
    weakness_tags TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE question_reviews (
    id TEXT PRIMARY KEY,
    review_report_id TEXT NOT NULL REFERENCES review_reports(id) ON DELETE CASCADE,
    interview_question_id TEXT NOT NULL REFERENCES interview_questions(id) ON DELETE CASCADE,
    evaluation TEXT NOT NULL,
    answer_evidence TEXT NOT NULL,
    missing_evidence TEXT NOT NULL,
    improvement_action TEXT NOT NULL,
    recommended_answer_structure TEXT NOT NULL,
    possible_followups TEXT NOT NULL
);

CREATE INDEX idx_interviews_user_id ON interviews(user_id);
CREATE INDEX idx_interview_questions_interview_id ON interview_questions(interview_id);
CREATE INDEX idx_review_reports_interview_id ON review_reports(interview_id);
CREATE INDEX idx_question_reviews_report_id ON question_reviews(review_report_id);
