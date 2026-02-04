package com.example.common.config;


public record FlyProps(String apiBaseUrl, String token, String appName, long timeoutMs, Master master) {
    public record Master(long jobDelayMs, long leaseSeconds) {
    }
}
