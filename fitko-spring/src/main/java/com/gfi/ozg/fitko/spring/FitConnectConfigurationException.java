package com.gfi.ozg.fitko.spring;

import lombok.experimental.StandardException;

/**
 * Thrown at application startup when {@code fitconnect.*} properties can't be
 * turned into a working FIT-Connect SDK configuration: a required property is
 * missing, a key resource can't be read, or the SDK itself rejected the
 * assembled configuration. Always a fail-fast, context-refresh-time error -
 * never thrown while handling a request.
 *
 * <p>{@code @StandardException} generates the usual {@code (String)}/{@code
 * (Throwable)}/{@code (String, Throwable)}/{@code ()} constructors, each
 * delegating to the matching {@code RuntimeException} super constructor.
 */
@StandardException
public class FitConnectConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;
}
