package com.gfi.ozg.fitko.spring;

/**
 * Thrown at application startup when {@code fitconnect.*} properties can't be
 * turned into a working FIT-Connect SDK configuration: a required property is
 * missing, a key resource can't be read, or the SDK itself rejected the
 * assembled configuration. Always a fail-fast, context-refresh-time error -
 * never thrown while handling a request.
 */
public class FitConnectConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FitConnectConfigurationException(String message) {
        super(message);
    }

    public FitConnectConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
