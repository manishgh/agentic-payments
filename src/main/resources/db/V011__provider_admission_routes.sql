CREATE TABLE payment_operation_routes (
 tenant_id text NOT NULL, run_id uuid NOT NULL, operation_id uuid NOT NULL,
 route text NOT NULL CHECK(route IN ('legacy-local-fake-1','local-provider-journal-admission-1')),
 binding_json text CHECK(octet_length(binding_json)<=131072),
 PRIMARY KEY(tenant_id,run_id,operation_id),
 FOREIGN KEY(tenant_id,run_id,operation_id) REFERENCES financial_operations(tenant_id,run_id,operation_id),
 CHECK((route='legacy-local-fake-1' AND binding_json IS NULL) OR (route='local-provider-journal-admission-1' AND binding_json IS NOT NULL))
);
INSERT INTO payment_operation_routes(tenant_id,run_id,operation_id,route,binding_json)
 SELECT tenant_id,run_id,operation_id,'legacy-local-fake-1',NULL FROM financial_operations;
