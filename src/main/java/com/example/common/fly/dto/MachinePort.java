package com.example.common.persistence.fly.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachinePort(
        int port,
        List<String> handlers
) {}
