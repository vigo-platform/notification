-- Prepared migration. Not in Flyway's default path; never auto-applied.
-- Run through the approved migration process, in the service-owned schema.
CREATE TABLE in_app_notification (
    id VARCHAR(36) COLLATE "C" PRIMARY KEY,
    owner_id VARCHAR(255) COLLATE "C" NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    read_at_ms BIGINT,
    CONSTRAINT in_app_read_time CHECK (read_at_ms IS NULL OR read_at_ms >= created_at_ms),
    CONSTRAINT in_app_owner_nonempty CHECK (length(trim(owner_id)) > 0),
    CONSTRAINT in_app_title_nonempty CHECK (length(trim(title)) > 0),
    CONSTRAINT in_app_body_nonempty CHECK (length(trim(body)) > 0)
);
CREATE INDEX in_app_owner_created ON in_app_notification (owner_id, created_at_ms DESC, id DESC);
CREATE INDEX in_app_owner_unread ON in_app_notification (owner_id, read_at_ms);
