CREATE TABLE provider_authentication_actions (
    tenant_id text NOT NULL,
    merchant_id text NOT NULL,
    run_id text NOT NULL,
    caller_key text NOT NULL,
    operation_id text NOT NULL,
    action_ref text NOT NULL,
    binding_json text NOT NULL,
    request_id uuid,
    subject_id text,
    decision text,
    observation_json text,
    PRIMARY KEY(tenant_id,merchant_id,run_id,caller_key),
    UNIQUE(tenant_id,merchant_id,run_id,operation_id),
    UNIQUE(tenant_id,merchant_id,run_id,action_ref),
    UNIQUE(tenant_id,merchant_id,run_id,request_id),
    CHECK ((request_id IS NULL AND subject_id IS NULL AND decision IS NULL AND observation_json IS NULL)
        OR (request_id IS NOT NULL AND subject_id IS NOT NULL AND decision IN ('approve','deny') AND observation_json IS NOT NULL))
);
