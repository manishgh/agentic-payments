ALTER TABLE financial_operations ADD COLUMN admitted_at timestamptz;
ALTER TABLE financial_operations ADD COLUMN admitted_grant_version int;
ALTER TABLE authority_grants ADD COLUMN revoked_at timestamptz;
ALTER TABLE authority_grants ADD COLUMN revoked_admission_sequence bigint;
UPDATE financial_operations o SET admitted_at=a.virtual_time,admitted_grant_version=p.grant_version FROM payment_attempts p JOIN audit_events a ON a.tenant_id=p.tenant_id AND a.run_id=p.run_id AND a.intent_id=p.intent_id AND a.state='ADMITTED' WHERE o.tenant_id=p.tenant_id AND o.run_id=p.run_id AND o.attempt_id=p.attempt_id AND o.admitted_sequence IS NOT NULL;
