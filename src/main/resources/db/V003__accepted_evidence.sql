ALTER TABLE operation_evidence ADD COLUMN accepted boolean NOT NULL DEFAULT false;
UPDATE operation_evidence e SET accepted=true FROM financial_operations o WHERE e.tenant_id=o.tenant_id AND e.run_id=o.run_id AND e.operation_id=o.operation_id AND e.sequence=o.fact_sequence AND e.outcome=COALESCE(o.final_outcome,'PENDING');
