package com.example.common.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Final persisted event document.
 * Idempotency:
 * - id is expected to be deterministic (e.g., String.valueOf(spoolStartOffset))
 * - re-processing the same spool record updates the same Mongo document, avoiding duplicates.
 */
@TypeAlias("EventDoc")
@Document("events")
public record EventDoc(
        @Id
        String id,
        String status,
        Instant receivedAt,
        Instant createdAt,
        Map<String, Object> metadata,
        String hash,
        Boolean duplicate,
        byte[] payload,
        @Indexed(name = "ttl_expireAt", expireAfter = "PT0S")
        Instant expireAt
) {
}
