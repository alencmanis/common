package com.example.common.fly.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMachineRequest(
        String name,
        String region,
        Map<String, Object> config,
        Boolean skip_launch,
        Map<String, Object> metadata
) {}
