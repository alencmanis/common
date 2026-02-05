package com.example.common.config;

public interface AutoscaleProps {
    boolean enabled();
    double lagThresholdSeconds();
    int scaleStep();
    int maxMachines();
    long cooldownSeconds();
    long minBacklogToScale();
    boolean suspendEnabled();
}
