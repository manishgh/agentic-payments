package io.agenticpayments.application;

import java.util.UUID;

/** Actual selection and committed activity from one worker call; null operation/kind means IDLE. */
public record WorkerReceipt(UUID operationId,String workKind,long fence,long firstAuditSequence,long lastAuditSequence,boolean progressed) {}
