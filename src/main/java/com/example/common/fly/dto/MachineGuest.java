package com.example.common.fly.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineGuest(
        Integer cpus,
        Integer memory_mb
) {}
