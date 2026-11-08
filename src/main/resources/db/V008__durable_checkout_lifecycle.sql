CREATE TABLE checkout_configurations (
 tenant_id text NOT NULL, run_id uuid NOT NULL, principal_id text NOT NULL,
 merchant_id text NOT NULL, configuration_id text NOT NULL, configuration_version text NOT NULL,
 metadata_json text NOT NULL CHECK(octet_length(metadata_json)<=4096),
 PRIMARY KEY(tenant_id,run_id,principal_id,merchant_id,configuration_id,configuration_version),
 FOREIGN KEY(tenant_id,run_id) REFERENCES simulation_runs
);
CREATE TABLE checkout_current_configurations (
 tenant_id text NOT NULL, run_id uuid NOT NULL, principal_id text NOT NULL,
 merchant_id text NOT NULL, configuration_id text NOT NULL, configuration_version text NOT NULL,
 PRIMARY KEY(tenant_id,run_id,principal_id,merchant_id,configuration_id),
 FOREIGN KEY(tenant_id,run_id,principal_id,merchant_id,configuration_id,configuration_version) REFERENCES checkout_configurations
);
CREATE TABLE checkout_purchases (
 tenant_id text NOT NULL, run_id uuid NOT NULL, principal_id text NOT NULL, purchase_id uuid NOT NULL,
 merchant_id text NOT NULL, configuration_id text NOT NULL, configuration_version text NOT NULL,
 purchase_json text NOT NULL CHECK(octet_length(purchase_json)<=8192),
 PRIMARY KEY(tenant_id,run_id,principal_id,purchase_id),
 FOREIGN KEY(tenant_id,run_id,principal_id,merchant_id,configuration_id,configuration_version) REFERENCES checkout_configurations
);
CREATE TABLE checkout_lifecycle_commands (
 tenant_id text NOT NULL, run_id uuid NOT NULL, principal_id text NOT NULL, purchase_id uuid NOT NULL,
 command_id uuid NOT NULL, local_key text NOT NULL CHECK(octet_length(local_key)<=255),
 fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
 immutable_json text NOT NULL CHECK(octet_length(immutable_json)<=262144),
 state text NOT NULL CHECK(state IN ('STAGED','CLAIMED','RECORDED','UNKNOWN','STALE')),
 claim_token uuid, outcome_code text NOT NULL, observation text NOT NULL CHECK(observation IN ('NOT_SENT','OBSERVED','UNKNOWN')),
 http_status int NOT NULL CHECK(http_status=0 OR http_status BETWEEN 100 AND 599), response_reference uuid,
 PRIMARY KEY(tenant_id,run_id,principal_id,purchase_id,command_id),
 UNIQUE(tenant_id,run_id,principal_id,purchase_id,local_key),
 FOREIGN KEY(tenant_id,run_id,principal_id,purchase_id) REFERENCES checkout_purchases,
 CHECK((state='CLAIMED')=(claim_token IS NOT NULL))
);
CREATE UNIQUE INDEX checkout_one_active_command ON checkout_lifecycle_commands(tenant_id,run_id,principal_id,purchase_id) WHERE state IN ('STAGED','CLAIMED');
CREATE TABLE checkout_lifecycle_outcomes (
 tenant_id text NOT NULL, run_id uuid NOT NULL, principal_id text NOT NULL, purchase_id uuid NOT NULL,
 command_id uuid NOT NULL, response_reference uuid NOT NULL, claim_token uuid NOT NULL,
 request_digest text NOT NULL CHECK(request_digest ~ '^[a-f0-9]{64}$'),
 result_json text NOT NULL CHECK(octet_length(result_json)<=262144), stale boolean NOT NULL,
 PRIMARY KEY(tenant_id,run_id,principal_id,purchase_id,response_reference),
 UNIQUE(tenant_id,run_id,principal_id,purchase_id,command_id,claim_token),
 FOREIGN KEY(tenant_id,run_id,principal_id,purchase_id,command_id) REFERENCES checkout_lifecycle_commands
);
