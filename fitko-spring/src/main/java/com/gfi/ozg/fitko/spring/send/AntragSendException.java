package com.gfi.ozg.fitko.spring.send;

import lombok.experimental.StandardException;

/**
 * Wraps a checked {@code FitConnectSenderException} so callers of {@link AntragSender} don't have to declare it.
 *
 * <p>{@code @StandardException} generates the usual {@code (String)}/{@code
 * (Throwable)}/{@code (String, Throwable)}/{@code ()} constructors; only
 * {@code (String, Throwable)} is actually used.
 */
@StandardException
public class AntragSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;
}
