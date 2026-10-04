package com.microchip.delta_core.service;

// A request the service will not take; code tells the page which words to use
public class Refusal extends RuntimeException {

    private final int status;
    private final String code;

    public Refusal(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
