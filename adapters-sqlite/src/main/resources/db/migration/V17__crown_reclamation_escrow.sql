CREATE TABLE crown_reclamation_escrow (
    recovery_operation TEXT PRIMARY KEY NOT NULL REFERENCES crown_reclamation(recovery_operation),
    definition_id TEXT NOT NULL,
    payload BLOB NOT NULL CHECK(length(payload) BETWEEN 1 AND 1048576),
    payload_sha256 TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('STAGED','HELD'))
);
