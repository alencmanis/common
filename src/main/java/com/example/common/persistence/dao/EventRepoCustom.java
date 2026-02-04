package com.example.common.persistence.dao;

import reactor.core.publisher.Mono;

public interface EventRepoCustom {
    Mono<Long> countNewWithoutProcessingAt();
}
