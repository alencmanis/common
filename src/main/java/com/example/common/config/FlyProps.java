package com.example.common.config;


public interface FlyProps {
    String apiBaseUrl();

    String token();

    String appName();

    long timeoutMs();

    Master master();

    interface Master {
        long jobDelayMs();

        long leaseSeconds();
    }
}


