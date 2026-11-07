ALTER TABLE payment_intents ADD COLUMN profile text NOT NULL DEFAULT 'local-fake-single-tender-0.1' CHECK(profile IN ('local-fake-single-tender-0.1','local-ap2-acp-single-tender-0.1'));
ALTER TABLE payment_attempts ADD CONSTRAINT native_attempt_binding UNIQUE(tenant_id,run_id,attempt_id,intent_id,checkout_revision,subject_id,grant_id,grant_version);
CREATE TABLE native_artifacts (
 tenant_id text NOT NULL, run_id uuid NOT NULL, artifact_id text NOT NULL, digest text NOT NULL,
 media_type text NOT NULL, profile text NOT NULL, content bytea NOT NULL,
 content_length integer NOT NULL CHECK(content_length>0 AND content_length<=131072),
 PRIMARY KEY(tenant_id,run_id,artifact_id)
);
CREATE TABLE native_checkouts (
 tenant_id text NOT NULL, run_id uuid NOT NULL, intent_id uuid NOT NULL, revision int NOT NULL,
 checkout_json text NOT NULL,
 PRIMARY KEY(tenant_id,run_id,intent_id,revision),
 FOREIGN KEY(tenant_id,run_id,intent_id,revision) REFERENCES checkout_snapshots
);
CREATE TABLE native_trust_keys (
 tenant_id text NOT NULL, run_id uuid NOT NULL, issuer text NOT NULL, kid text NOT NULL, purpose text NOT NULL,
 public_key bytea NOT NULL, valid_from timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 version int NOT NULL CHECK(version>0), revoked boolean NOT NULL DEFAULT false,
 PRIMARY KEY(tenant_id,run_id,issuer,kid,purpose),
 FOREIGN KEY(tenant_id,run_id) REFERENCES simulation_runs,
 CHECK(expires_at>valid_from)
);
CREATE TABLE native_authorities (
 tenant_id text NOT NULL, run_id uuid NOT NULL, attempt_id uuid NOT NULL, intent_id uuid NOT NULL, revision int NOT NULL,
 subject_id text NOT NULL, grant_id text NOT NULL, grant_version int NOT NULL,
 authority_json text NOT NULL, authority_digest text NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,run_id,attempt_id),
 UNIQUE(tenant_id,authority_digest),
 UNIQUE(tenant_id,run_id,attempt_id,intent_id,revision,subject_id,grant_id,grant_version),
 FOREIGN KEY(tenant_id,run_id,attempt_id,intent_id,revision,subject_id,grant_id,grant_version) REFERENCES payment_attempts(tenant_id,run_id,attempt_id,intent_id,checkout_revision,subject_id,grant_id,grant_version),
 FOREIGN KEY(tenant_id,run_id,intent_id,revision) REFERENCES native_checkouts,
 FOREIGN KEY(tenant_id,run_id,grant_id) REFERENCES authority_grants
);
CREATE TABLE native_challenges (
 tenant_id text NOT NULL, run_id uuid NOT NULL, role text NOT NULL, nonce text NOT NULL,
 intent_id uuid NOT NULL, revision int NOT NULL, subject_id text NOT NULL,
 grant_id text NOT NULL, grant_version int NOT NULL, issuer text NOT NULL, audience text NOT NULL,
 checkout_digest text NOT NULL, expires_at timestamptz NOT NULL, attempt_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,run_id,role,nonce),
 FOREIGN KEY(tenant_id,run_id,intent_id,revision) REFERENCES native_checkouts,
 FOREIGN KEY(tenant_id,run_id,grant_id) REFERENCES authority_grants,
 FOREIGN KEY(tenant_id,run_id,attempt_id,intent_id,revision,subject_id,grant_id,grant_version) REFERENCES native_authorities(tenant_id,run_id,attempt_id,intent_id,revision,subject_id,grant_id,grant_version)
);
CREATE TABLE native_replay_identities (
 tenant_id text NOT NULL, role text NOT NULL, issuer text NOT NULL, jti text NOT NULL,
 run_id uuid NOT NULL, attempt_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,role,issuer,jti),
 FOREIGN KEY(tenant_id,run_id,attempt_id) REFERENCES native_authorities
);
