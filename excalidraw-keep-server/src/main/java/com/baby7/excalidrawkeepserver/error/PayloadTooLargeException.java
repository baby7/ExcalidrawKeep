package com.baby7.excalidrawkeepserver.error;

public class PayloadTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
