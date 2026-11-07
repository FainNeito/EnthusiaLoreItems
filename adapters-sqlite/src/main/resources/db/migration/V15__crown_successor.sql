ALTER TABLE crown_handoff_escrow ADD COLUMN recipient_operation TEXT;
CREATE UNIQUE INDEX crown_handoff_recipient ON crown_handoff_escrow(recipient_operation);
CREATE TABLE crown_prior_audit AS SELECT * FROM crown_custody_audit WHERE 0;
CREATE TABLE crown_prior_helmets AS SELECT * FROM crown_helmet_evidence WHERE 0;
