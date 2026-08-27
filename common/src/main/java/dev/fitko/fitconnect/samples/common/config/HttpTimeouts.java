package dev.fitko.fitconnect.samples.common.config;

/**
 * Optional {@code httpConfig.timeouts} overrides. Unset values keep the SDK's
 * default of 30 seconds.
 */
public final class HttpTimeouts {

    private Integer connectionTimeoutSeconds;
    private Integer readTimeoutSeconds;
    private Integer writeTimeoutSeconds;

    public void setConnectionTimeoutSeconds(Integer connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(Integer readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public void setWriteTimeoutSeconds(Integer writeTimeoutSeconds) {
        this.writeTimeoutSeconds = writeTimeoutSeconds;
    }

    public boolean isEmpty() {
        return connectionTimeoutSeconds == null && readTimeoutSeconds == null && writeTimeoutSeconds == null;
    }

    public void writeTo(YamlWriter yaml) {
        if (isEmpty()) {
            return;
        }
        yaml.line(0, "httpConfig:");
        yaml.line(1, "timeouts:");
        if (connectionTimeoutSeconds != null) {
            yaml.keyValueRaw(2, "connectionTimeout", connectionTimeoutSeconds.toString());
        }
        if (readTimeoutSeconds != null) {
            yaml.keyValueRaw(2, "readTimeout", readTimeoutSeconds.toString());
        }
        if (writeTimeoutSeconds != null) {
            yaml.keyValueRaw(2, "writeTimeout", writeTimeoutSeconds.toString());
        }
    }
}
