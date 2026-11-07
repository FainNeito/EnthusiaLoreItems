CREATE TABLE crown_handoff_escrow (
    operation_id TEXT PRIMARY KEY NOT NULL REFERENCES crown_succession_requests(operation_id),
    payload BLOB NOT NULL CHECK (length(payload) BETWEEN 1 AND 1048576),
    released INTEGER NOT NULL DEFAULT 0 CHECK (released IN (0,1))
);
