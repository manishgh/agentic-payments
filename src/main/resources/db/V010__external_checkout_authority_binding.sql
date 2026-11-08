ALTER TABLE checkout_lifecycle_outcomes ADD COLUMN observed_at timestamptz;
ALTER TABLE payment_intents DROP CONSTRAINT payment_intents_profile_check;
ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_profile_check CHECK(profile IN (
 'local-fake-single-tender-0.1','local-ap2-acp-single-tender-0.1','local-external-ap2-acp-single-tender-0.1'
));
CREATE TABLE external_checkout_bindings (
 tenant_id text NOT NULL, run_id uuid NOT NULL, principal_id text NOT NULL, purchase_id uuid NOT NULL,
 intent_id uuid NOT NULL, command_id uuid NOT NULL, response_reference uuid NOT NULL,
 purchase_revision integer NOT NULL CHECK(purchase_revision>0),
 configuration_json text NOT NULL CHECK(octet_length(configuration_json)<=4096),
 observed_at timestamptz NOT NULL, raw_digest text NOT NULL CHECK(raw_digest ~ '^sha256:[a-f0-9]{64}$'),
 expires_at timestamptz NOT NULL,
 attestation_provenance text NOT NULL CHECK(attestation_provenance='local-synthetic-attestation-0.1'),
 PRIMARY KEY(tenant_id,run_id,principal_id,purchase_id),
 UNIQUE(tenant_id,run_id,intent_id),
 FOREIGN KEY(tenant_id,run_id,principal_id,purchase_id) REFERENCES checkout_purchases,
 FOREIGN KEY(tenant_id,run_id,principal_id,purchase_id,command_id) REFERENCES checkout_lifecycle_commands,
 FOREIGN KEY(tenant_id,run_id,principal_id,purchase_id,response_reference) REFERENCES checkout_lifecycle_outcomes,
 FOREIGN KEY(tenant_id,run_id,intent_id) REFERENCES payment_intents,
 CHECK(expires_at>observed_at)
);
