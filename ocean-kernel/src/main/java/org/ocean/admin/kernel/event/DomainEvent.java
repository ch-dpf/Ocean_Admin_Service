package org.ocean.admin.kernel.event;

import java.time.Instant;
import java.util.UUID;

/** Marker contract for events exchanged across module boundaries. */
public interface DomainEvent {
    UUID eventId();

    Instant occurredAt();
}

