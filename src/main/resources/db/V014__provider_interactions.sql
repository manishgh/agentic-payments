ALTER TABLE payment_operation_routes DROP CONSTRAINT payment_operation_routes_route_check;
ALTER TABLE payment_operation_routes DROP CONSTRAINT payment_operation_routes_check;
ALTER TABLE payment_operation_routes ADD CHECK(route IN ('legacy-local-fake-1','local-provider-journal-admission-1','local-provider-interactions-1'));
ALTER TABLE payment_operation_routes ADD CHECK((route='legacy-local-fake-1' AND binding_json IS NULL) OR (route IN ('local-provider-journal-admission-1','local-provider-interactions-1') AND binding_json IS NOT NULL));
CREATE TABLE customer_action_provider_bindings (
 tenant_id text NOT NULL,run_id uuid NOT NULL,action_id uuid NOT NULL,operation_id uuid NOT NULL,
 binding_json text NOT NULL CHECK(octet_length(binding_json)<=131072),
 authentication_json text CHECK(octet_length(authentication_json)<=131072),
 PRIMARY KEY(tenant_id,run_id,action_id),UNIQUE(tenant_id,run_id,operation_id),
 FOREIGN KEY(tenant_id,run_id,action_id) REFERENCES customer_actions,
 FOREIGN KEY(tenant_id,run_id,operation_id) REFERENCES payment_operation_routes
);
CREATE TABLE provider_action_commands (
 tenant_id text NOT NULL,run_id uuid NOT NULL,principal_id text NOT NULL,action_id uuid NOT NULL,
 idempotency_key text NOT NULL,request_id uuid NOT NULL,request_digest text NOT NULL,
 expected_version int NOT NULL CHECK(expected_version>0),subject_id text NOT NULL,
 decision text NOT NULL CHECK(decision IN ('approve','deny')),requested_at text NOT NULL,
 binding_json text NOT NULL CHECK(octet_length(binding_json)<=131072),
 PRIMARY KEY(tenant_id,run_id,principal_id,action_id,idempotency_key),
 UNIQUE(tenant_id,run_id,action_id),UNIQUE(request_id),
 FOREIGN KEY(tenant_id,run_id,action_id) REFERENCES customer_action_provider_bindings
);
