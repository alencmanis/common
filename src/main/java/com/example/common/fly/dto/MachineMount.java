package com.example.common.fly.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineMount(
        String volume,
        String path
) {}
