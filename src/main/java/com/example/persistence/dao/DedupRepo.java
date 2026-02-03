package com.example.persistence.dao;

import com.example.persistence.entity.DedupDoc;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface DedupRepo extends ReactiveMongoRepository<DedupDoc, String> {
    Mono<DedupDoc> findFirstByHashAndReceivedAtGreaterThan(String hash, Instant receivedAt);
}
