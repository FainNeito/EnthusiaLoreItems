CREATE TABLE crown_reclamation (
    recovery_operation TEXT PRIMARY KEY NOT NULL,
    source_operation TEXT NOT NULL UNIQUE,
    instance_id TEXT NOT NULL,
    holder_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation>=0),
    recorded_at INTEGER NOT NULL,
    UNIQUE(instance_id,generation)
);
