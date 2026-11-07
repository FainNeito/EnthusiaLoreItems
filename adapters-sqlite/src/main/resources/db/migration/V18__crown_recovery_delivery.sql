CREATE TABLE crown_recovery_delivery (
    recovery_operation TEXT PRIMARY KEY NOT NULL REFERENCES crown_reclamation(recovery_operation),
    source_operation TEXT NOT NULL UNIQUE,
    instance_id TEXT NOT NULL,
    holder_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation>=0),
    match_id TEXT NOT NULL UNIQUE,
    winner_id TEXT NOT NULL,
    recipient_operation TEXT UNIQUE,
    UNIQUE(instance_id,generation)
);
