CREATE TABLE notification_v2_command (
    producer VARCHAR(64) COLLATE "C" NOT NULL,
    event_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    accepted_at_ms BIGINT NOT NULL,
    PRIMARY KEY (producer, event_id)
);

CREATE TABLE notification_v2_delivery (
    producer VARCHAR(64) COLLATE "C" NOT NULL,
    event_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL CHECK (channel = 'IN_APP'),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'SUCCEEDED', 'EXPIRED', 'EXHAUSTED', 'AUTHORIZATION_REVOKED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_ms BIGINT NOT NULL,
    expires_epoch_seconds TEXT,
    owner_id VARCHAR(19) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    history_id VARCHAR(36),
    PRIMARY KEY (producer, event_id, channel),
    FOREIGN KEY (producer, event_id) REFERENCES notification_v2_command (producer, event_id),
    UNIQUE (history_id),
    CHECK ((state = 'SUCCEEDED') = (history_id IS NOT NULL))
);

CREATE INDEX notification_v2_pending ON notification_v2_delivery
    (next_attempt_ms, producer, event_id) WHERE state = 'PENDING';

-- Persist terminal poison decisions without copying any untrusted fields from the record.
CREATE TABLE notification_v2_rejection (
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    rejected_at_ms BIGINT NOT NULL,
    PRIMARY KEY (partition_id, record_offset)
);
