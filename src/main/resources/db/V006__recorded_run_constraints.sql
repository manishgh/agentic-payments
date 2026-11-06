ALTER TABLE simulation_runs ADD CONSTRAINT replay_owner_required CHECK((execution_mode='INTERACTIVE' AND replay_owner IS NULL) OR (execution_mode='REPLAY' AND replay_owner IS NOT NULL));
ALTER TABLE simulation_runs ADD CONSTRAINT nonnegative_run_order CHECK(next_work_sequence>=0 AND next_intent_sequence>=0);
ALTER TABLE payment_intents ADD CONSTRAINT positive_intent_order CHECK(creation_sequence>0);
ALTER TABLE outbox ADD CONSTRAINT positive_work_order CHECK(enqueue_sequence>0);
