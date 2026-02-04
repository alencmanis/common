package com.example.common.fly.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineConfig(
        List<MachineService> services,
        Map<String, String> env,
        List<MachineMount> mounts,
        MachineGuest guest,
        String image
) {
}
