CREATE TABLE crown_custody (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    operation_id TEXT NOT NULL UNIQUE,
    instance_id TEXT NOT NULL,
    holder_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK (generation >= 0),
    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'REVIEW_REQUIRED', 'EQUIPPED'))
);

CREATE TABLE crown_custody_audit (
    operation_id TEXT NOT NULL,
    transition TEXT NOT NULL,
    instance_id TEXT NOT NULL,
    holder_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    recorded_at INTEGER NOT NULL,
    PRIMARY KEY (operation_id, transition)
);

CREATE TABLE crown_helmet_evidence (
    operation_id TEXT PRIMARY KEY NOT NULL,
    instance_id TEXT NOT NULL,
    holder_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    payload BLOB NOT NULL,
    payload_sha256 TEXT NOT NULL,
    is_empty INTEGER NOT NULL CHECK (is_empty IN (0, 1))
);
