package com.moneycompass.common;

import org.springframework.http.HttpStatus;

/** Base for exceptions that map cleanly onto an HTTP status. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    protected ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() { return status; }
    public String getTitle() { return title; }
}
