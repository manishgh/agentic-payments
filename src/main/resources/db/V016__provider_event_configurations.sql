-- Explicit trusted enrollment only. Historical deliveries and envelopes are never backfilled.
CREATE TABLE provider_event_configurations (
    route_digest varchar(71) PRIMARY KEY,
    route_json text NOT NULL CHECK(octet_length(route_json)<=16384),
    descriptor_digest varchar(71) NOT NULL CHECK(descriptor_digest ~ '^sha256:[a-f0-9]{64}$'),
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
