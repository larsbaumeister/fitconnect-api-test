package com.gfi.ozg.fitko.spring.send;

/** Wraps a checked {@code FitConnectSenderException} so callers of {@link AntragSender} don't have to declare it. */
public class AntragSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AntragSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
