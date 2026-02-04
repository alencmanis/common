package com.example.common.persistence.fly.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record ManagedMachineSpec(
        @NotBlank String role,
        @NotBlank String region,
        @NotBlank String image,
        Map<String, String> env,
        Map<String, Object> metadata,
        Guest guest
) {
    public record Guest(@NotNull Integer cpus, @NotNull Integer memoryMb) {}
}