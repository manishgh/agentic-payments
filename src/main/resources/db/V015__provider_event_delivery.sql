-- Explicit opt-in only. V013 retained envelopes and disputes remain caller-driven.
CREATE TABLE provider_event_delivery (
    delivery_ref varchar(128) PRIMARY KEY,
    kind varchar(16) NOT NULL CHECK(kind IN ('EVENT','QUARANTINE')),
    tenant_id varchar(128) NOT NULL,
    merchant_id varchar(128) NOT NULL,
    run_id varchar(128) NOT NULL,
    operation_fingerprint varchar(71) NOT NULL,
    operation_json text NOT NULL CHECK(octet_length(operation_json)<=131072),
    envelope_ref varchar(128) UNIQUE REFERENCES provider_event_envelopes(envelope_ref),
    policy_ref varchar(128) NOT NULL,
    policy_version varchar(128) NOT NULL,
    key_ref varchar(128) NOT NULL,
    key_version varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','DONE','DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0 AND attempts<=max_attempts),
    max_attempts integer NOT NULL DEFAULT 8 CHECK(max_attempts=8),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    failure_code varchar(32) NOT NULL DEFAULT 'NONE' CHECK(failure_code IN ('NONE','INVALID_EVENT','CONFIGURATION_UNAVAILABLE','TEMPORARILY_UNAVAILABLE','CONFLICT','INTERNAL','ATTEMPTS_EXHAUSTED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((kind='EVENT')=(envelope_ref IS NOT NULL)),
    CHECK((status='LEASED')=(lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK(status='LEASED' OR (lease_token IS NULL AND lease_until IS NULL))
);
CREATE UNIQUE INDEX provider_event_delivery_quarantine_owner ON provider_event_delivery(tenant_id,merchant_id,run_id,operation_fingerprint) WHERE kind='QUARANTINE';
CREATE INDEX provider_event_delivery_available ON provider_event_delivery(tenant_id,merchant_id,run_id,available_at,created_at) WHERE status IN ('READY','LEASED');
