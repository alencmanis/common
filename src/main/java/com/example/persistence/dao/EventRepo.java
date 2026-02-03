package com.example.persistence.dao;

import com.example.persistence.entity.EventDoc;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface EventRepo extends ReactiveMongoRepository<EventDoc, String> {
}
