package com.vendoryx.service;

public class BusinessException extends Exception {
    private final int httpStatus;

    public BusinessException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
