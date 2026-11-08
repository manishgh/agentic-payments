package io.agenticpayments.observability;

import java.time.Instant;

public record LogRecord(Instant timestamp, String category, LogLevel level, EventDefinition event, LogFields fields) { }
