package com.moneycompass.common;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {
    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, "Conflict", detail);
    }
}
