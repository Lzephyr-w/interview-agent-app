CREATE TABLE ai_conversations (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    interview_package_id TEXT REFERENCES interview_packages(id) ON DELETE SET NULL,
    interview_id TEXT REFERENCES interviews(id) ON DELETE SET NULL,
    review_report_id TEXT REFERENCES review_reports(id) ON DELETE SET NULL,
    weakness_tag TEXT,
    title TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_conversation_messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL CHECK (status IN ('SAVED', 'PENDING', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    client_request_id TEXT,
    reply_to_message_id TEXT REFERENCES ai_conversation_messages(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_conversation_client_request UNIQUE (conversation_id, client_request_id),
    CONSTRAINT uq_ai_conversation_reply UNIQUE (conversation_id, reply_to_message_id)
);

CREATE INDEX idx_ai_conversations_user_id ON ai_conversations(user_id);
CREATE INDEX idx_ai_conversation_messages_conversation_id ON ai_conversation_messages(conversation_id, created_at);
