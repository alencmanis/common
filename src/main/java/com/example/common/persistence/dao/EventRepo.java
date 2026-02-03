package com.example.common.persistence.dao;

import com.example.common.persistence.entity.EventDoc;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface EventRepo extends ReactiveMongoRepository<EventDoc, String> {
}
