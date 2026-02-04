package com.example.common.fly.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Machine(
        String id,
        String name,
        String state,
        String region,
        Map<String, Object> config,
        Map<String, Object> metadata
) {}
