CREATE TABLE provider_compatibility_bindings (
 tenant_id text NOT NULL CHECK(tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 merchant_id text NOT NULL CHECK(merchant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 run_id text NOT NULL CHECK(run_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 caller_key text NOT NULL CHECK(caller_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 operation_id text NOT NULL CHECK(operation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 operation_fingerprint text NOT NULL CHECK(operation_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
 operation_json text NOT NULL CHECK(octet_length(operation_json)<=65536),
 legacy_tenant_id text NOT NULL, legacy_run_id uuid NOT NULL, legacy_adapter_key uuid NOT NULL,
 legacy_json text NOT NULL CHECK(octet_length(legacy_json)<=8192),
 codec_version text NOT NULL CHECK(codec_version='provider-compatibility-json-1'),
 mapping_policy_version text NOT NULL, scenario_policy_ref text NOT NULL, scenario_policy_version text NOT NULL,
 dispatch_claimed boolean NOT NULL DEFAULT false, dispatch_blocked boolean NOT NULL DEFAULT false,
 submission_fingerprint text CHECK(submission_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
 submission_json text CHECK(octet_length(submission_json)<=65536),
 prepared_resource_present boolean, prepared_resource_ref text,
 projection_evidence_id text, conflicted boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(tenant_id,merchant_id,run_id,caller_key),
 UNIQUE(tenant_id,merchant_id,run_id,operation_id),
 UNIQUE(legacy_tenant_id,legacy_run_id,legacy_adapter_key),
 CHECK((submission_json IS NULL AND submission_fingerprint IS NULL AND prepared_resource_present IS NULL AND prepared_resource_ref IS NULL)
    OR (submission_json IS NOT NULL AND submission_fingerprint IS NOT NULL AND prepared_resource_present IS FALSE AND prepared_resource_ref IS NULL)),
 CHECK(NOT dispatch_claimed OR submission_json IS NOT NULL),
 CHECK(submission_json IS NULL OR dispatch_claimed OR dispatch_blocked)
);
CREATE TABLE provider_compatibility_evidence (
 tenant_id text NOT NULL, merchant_id text NOT NULL, run_id text NOT NULL, caller_key text NOT NULL,
 evidence_id text NOT NULL CHECK(evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 codec_version text NOT NULL CHECK(codec_version='provider-compatibility-json-1'),
 evidence_json text NOT NULL CHECK(octet_length(evidence_json)<=131072),
 evidence_digest text NOT NULL CHECK(evidence_digest ~ '^sha256:[a-f0-9]{64}$'),
 state text NOT NULL CHECK(state IN ('UNKNOWN','PENDING','CAPTURED','NO_EFFECT_FENCED')),
 source text NOT NULL CHECK(source IN ('SUBMIT','RECONCILIATION','VERIFIED_EVENT','FENCE')),
 observed_at text NOT NULL, created_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(tenant_id,merchant_id,run_id,caller_key,evidence_id),
 FOREIGN KEY(tenant_id,merchant_id,run_id,caller_key) REFERENCES provider_compatibility_bindings
);
ALTER TABLE provider_compatibility_bindings ADD CONSTRAINT provider_compatibility_projection_fk
 FOREIGN KEY(tenant_id,merchant_id,run_id,caller_key,projection_evidence_id)
 REFERENCES provider_compatibility_evidence(tenant_id,merchant_id,run_id,caller_key,evidence_id);
CREATE TABLE provider_compatibility_resource_owners (
 provider_id text NOT NULL, account_ref text NOT NULL, region text NOT NULL,
 resource_ref text NOT NULL CHECK(resource_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
 tenant_id text NOT NULL, merchant_id text NOT NULL, run_id text NOT NULL, caller_key text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(provider_id,account_ref,region,resource_ref),
 UNIQUE(tenant_id,merchant_id,run_id,caller_key),
 FOREIGN KEY(tenant_id,merchant_id,run_id,caller_key) REFERENCES provider_compatibility_bindings
);
CREATE TABLE provider_compatibility_conflicts (
 receipt_id uuid PRIMARY KEY,
 requested_tenant_id text NOT NULL, requested_merchant_id text NOT NULL, requested_run_id text NOT NULL, requested_caller_key text NOT NULL,
 owner_tenant_id text, owner_merchant_id text, owner_run_id text, owner_caller_key text,
 kind text NOT NULL CHECK(kind IN ('BIND_IDENTITY','LEGACY_ALIAS','OPERATION_ALIAS','SUBMISSION_IDENTITY','EVIDENCE_ID','FINANCIAL_STATE','RESOURCE_OWNER','RESOURCE_REFERENCE','UNSUPPORTED_EVIDENCE','CORRUPT_STORAGE')),
 code text NOT NULL CHECK(code IN ('IDENTITY_CONFLICT','EVIDENCE_CONFLICT','CONTRACT_VIOLATION')),
 certainty text NOT NULL CHECK(certainty IN ('NOT_STARTED','MAY_HAVE_OCCURRED')),
 existing_digest text CHECK(existing_digest ~ '^sha256:[a-f0-9]{64}$'),
 candidate_digest text CHECK(candidate_digest ~ '^sha256:[a-f0-9]{64}$'),
 candidate_evidence_json text CHECK(octet_length(candidate_evidence_json)<=131072),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 FOREIGN KEY(owner_tenant_id,owner_merchant_id,owner_run_id,owner_caller_key) REFERENCES provider_compatibility_bindings,
 CHECK((owner_tenant_id IS NULL AND owner_merchant_id IS NULL AND owner_run_id IS NULL AND owner_caller_key IS NULL)
    OR (owner_tenant_id IS NOT NULL AND owner_merchant_id IS NOT NULL AND owner_run_id IS NOT NULL AND owner_caller_key IS NOT NULL))
);
CREATE INDEX provider_compatibility_conflict_scope_idx ON provider_compatibility_conflicts(requested_tenant_id,requested_merchant_id,requested_run_id,requested_caller_key);
