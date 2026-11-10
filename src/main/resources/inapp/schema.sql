-- Explicitly applied by tests only. Production database/migration wiring is pending.
-- Millisecond UTC instants avoid JDBC session-timezone conversion.
CREATE TABLE in_app_notification (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    read_at_ms BIGINT,
    CONSTRAINT in_app_read_time CHECK (read_at_ms IS NULL OR read_at_ms >= created_at_ms)
);
CREATE INDEX in_app_owner_created ON in_app_notification (owner_id, created_at_ms DESC, id DESC);
CREATE INDEX in_app_owner_unread ON in_app_notification (owner_id, read_at_ms);
