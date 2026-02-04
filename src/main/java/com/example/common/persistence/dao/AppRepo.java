package com.example.common.persistence.dao;


import com.example.common.persistence.entity.AppDoc;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

public interface AppRepo extends ReactiveMongoRepository<AppDoc, String> {
    Mono<Boolean> existsByMasterMachineId(String masterMachineId);
}
