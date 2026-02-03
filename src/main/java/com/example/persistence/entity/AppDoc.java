package com.example.persistence.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@TypeAlias("AppDoc")
@Document("apps")
public record AppDoc(
        @Id
        String id,

        @Indexed(unique = true)
        String appName,

        String masterMachineId,

        Instant masterLeaseUntil,

        Instant updatedAt
) {}
