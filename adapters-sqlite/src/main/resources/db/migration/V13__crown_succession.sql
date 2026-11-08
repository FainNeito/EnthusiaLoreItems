CREATE TABLE crown_succession_requests (
    operation_id TEXT PRIMARY KEY NOT NULL,
    instance_id TEXT NOT NULL,
    holder_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    killer_id TEXT NOT NULL CHECK (killer_id <> holder_id),
    recorded_at INTEGER NOT NULL,
    UNIQUE (instance_id, generation)
);
