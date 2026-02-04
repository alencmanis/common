package com.example.common.persistence.fly;

public class FlyApiException extends RuntimeException {
    private final int status;

    public FlyApiException(int status, String body) {
        super("Fly API error status=" + status + " body=" + body);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
