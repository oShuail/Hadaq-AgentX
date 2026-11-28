package com.example.hadaq.Api;

import lombok.Getter;

// status: 400 by default. 404 for an unknown building / photo / run, 503 when a needed service is not running
@Getter
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(String message) {
        this(400, message);
    }

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(503, message);
    }
}
