package com.example.common.persistence.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class EventRepoImpl implements EventRepoCustom {

    private final ReactiveMongoOperations mongo;

    @Override
    public Mono<Long> countNewWithoutProcessingAt() {
        Query q = new Query()
                .addCriteria(Criteria.where("status").is("NEW"))
                .addCriteria(new Criteria().orOperator(
                        Criteria.where("processingAt").exists(false),
                        Criteria.where("processingAt").is(null)
                ));
        return mongo.count(q, "events");
    }
}
