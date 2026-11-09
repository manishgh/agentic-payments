CREATE TABLE provider_event_envelopes (
    envelope_ref varchar(128) PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    merchant_id varchar(128) NOT NULL,
    run_id varchar(128) NOT NULL,
    operation_fingerprint varchar(71) NOT NULL,
    operation_json text NOT NULL,
    raw_body bytea NOT NULL CHECK (octet_length(raw_body) BETWEEN 1 AND 16384),
    signature varchar(64) NOT NULL,
    policy_ref varchar(128) NOT NULL,
    policy_version varchar(128) NOT NULL,
    key_ref varchar(128) NOT NULL,
    key_version varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE provider_verified_events (
    tenant_id varchar(128) NOT NULL,
    merchant_id varchar(128) NOT NULL,
    run_id varchar(128) NOT NULL,
    provider_id varchar(128) NOT NULL,
    account_ref varchar(128) NOT NULL,
    event_id varchar(128) NOT NULL,
    verified_ref varchar(128) NOT NULL UNIQUE,
    envelope_ref varchar(128) NOT NULL REFERENCES provider_event_envelopes(envelope_ref),
    operation_fingerprint varchar(71) NOT NULL,
    operation_json text NOT NULL,
    evidence_json text NOT NULL,
    PRIMARY KEY(tenant_id,merchant_id,run_id,provider_id,account_ref,event_id)
);

CREATE TABLE provider_event_quarantine (
    tenant_id varchar(128) NOT NULL,
    merchant_id varchar(128) NOT NULL,
    run_id varchar(128) NOT NULL,
    operation_fingerprint varchar(71) NOT NULL,
    operation_json text NOT NULL,
    PRIMARY KEY(tenant_id,merchant_id,run_id,operation_fingerprint)
);

CREATE TABLE provider_event_conflicts (
    conflict_ref uuid PRIMARY KEY,
    original_verified_ref varchar(128) NOT NULL REFERENCES provider_verified_events(verified_ref),
    disputed_envelope_ref varchar(128) NOT NULL UNIQUE REFERENCES provider_event_envelopes(envelope_ref),
    disputed_evidence_json text NOT NULL,
    reason varchar(32) NOT NULL CHECK(reason='EVENT_ID_CONFLICT'),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
