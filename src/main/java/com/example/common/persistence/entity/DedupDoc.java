package com.example.common.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@TypeAlias("DedupDoc")
@Document("dedups")
@CompoundIndex(
        name = "uq_dedups_receivedAt_hash",
        def = "{'receivedAt': 1, 'hash': 1}",
        unique = true
)
public record DedupDoc(
        @Id
        String id,
        Instant receivedAt,
        String hash,
        @Indexed(name = "ttl_expireAt", expireAfter = "PT0S")
        Instant expireAt
) {
}
