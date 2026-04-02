package io.github.djylb.smux4j;

import java.time.Duration;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Session configuration mapped from the original smux package, with a Java-style builder.
 */
public final class Config {
    public static final int VERSION_1 = 1;
    public static final int VERSION_2 = 2;

    private int version = VERSION_1;
    private boolean keepAliveDisabled;
    private Duration keepAliveInterval = Duration.ofSeconds(10);
    private Duration keepAliveTimeout = Duration.ofSeconds(30);
    private int maxFrameSize = 32_768;
    private int maxReceiveBuffer = 4_194_304;
    private int maxStreamBuffer = 65_536;

    public static Config defaultConfig() {
        return new Config();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Config copy() {
        Config copy = new Config();
        copy.version = version;
        copy.keepAliveDisabled = keepAliveDisabled;
        copy.keepAliveInterval = keepAliveInterval;
        copy.keepAliveTimeout = keepAliveTimeout;
        copy.maxFrameSize = maxFrameSize;
        copy.maxReceiveBuffer = maxReceiveBuffer;
        copy.maxStreamBuffer = maxStreamBuffer;
        return copy;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public Config validate() {
        Objects.requireNonNull(keepAliveInterval, "keepAliveInterval");
        Objects.requireNonNull(keepAliveTimeout, "keepAliveTimeout");
        if (version != VERSION_1 && version != VERSION_2) {
            throw new IllegalArgumentException("unsupported protocol version");
        }
        if (!keepAliveDisabled) {
            if (keepAliveInterval.isZero() || keepAliveInterval.isNegative()) {
                throw new IllegalArgumentException("keep-alive interval must be positive");
            }
            if (keepAliveTimeout.compareTo(keepAliveInterval) < 0) {
                throw new IllegalArgumentException("keep-alive timeout must be larger than keep-alive interval");
            }
        }
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("max frame size must be positive");
        }
        if (maxFrameSize > 65_535) {
            throw new IllegalArgumentException("max frame size must not be larger than 65535");
        }
        if (maxReceiveBuffer <= 0) {
            throw new IllegalArgumentException("max receive buffer must be positive");
        }
        if (maxStreamBuffer <= 0) {
            throw new IllegalArgumentException("max stream buffer must be positive");
        }
        if (maxStreamBuffer > maxReceiveBuffer) {
            throw new IllegalArgumentException("max stream buffer must not be larger than max receive buffer");
        }
        return this;
    }

    public int getVersion() {
        return version;
    }

    public Config setVersion(int version) {
        this.version = version;
        return this;
    }

    public boolean isKeepAliveDisabled() {
        return keepAliveDisabled;
    }

    public Config setKeepAliveDisabled(boolean keepAliveDisabled) {
        this.keepAliveDisabled = keepAliveDisabled;
        return this;
    }

    public Duration getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public Config setKeepAliveInterval(Duration keepAliveInterval) {
        this.keepAliveInterval = Objects.requireNonNull(keepAliveInterval, "keepAliveInterval");
        return this;
    }

    public long getKeepAliveIntervalMillis() {
        return keepAliveInterval.toMillis();
    }

    public Config setKeepAliveIntervalMillis(long keepAliveIntervalMillis) {
        return setKeepAliveInterval(Duration.ofMillis(keepAliveIntervalMillis));
    }

    public Duration getKeepAliveTimeout() {
        return keepAliveTimeout;
    }

    public Config setKeepAliveTimeout(Duration keepAliveTimeout) {
        this.keepAliveTimeout = Objects.requireNonNull(keepAliveTimeout, "keepAliveTimeout");
        return this;
    }

    public long getKeepAliveTimeoutMillis() {
        return keepAliveTimeout.toMillis();
    }

    public Config setKeepAliveTimeoutMillis(long keepAliveTimeoutMillis) {
        return setKeepAliveTimeout(Duration.ofMillis(keepAliveTimeoutMillis));
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public Config setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
        return this;
    }

    public int getMaxReceiveBuffer() {
        return maxReceiveBuffer;
    }

    public Config setMaxReceiveBuffer(int maxReceiveBuffer) {
        this.maxReceiveBuffer = maxReceiveBuffer;
        return this;
    }

    public int getMaxStreamBuffer() {
        return maxStreamBuffer;
    }

    public Config setMaxStreamBuffer(int maxStreamBuffer) {
        this.maxStreamBuffer = maxStreamBuffer;
        return this;
    }

    public static final class Builder {
        private final Config config;

        private Builder() {
            this.config = new Config();
        }

        private Builder(Config config) {
            this.config = config.copy();
        }

        public Builder version(int version) {
            config.setVersion(version);
            return this;
        }

        public Builder keepAliveDisabled(boolean keepAliveDisabled) {
            config.setKeepAliveDisabled(keepAliveDisabled);
            return this;
        }

        public Builder keepAliveInterval(Duration keepAliveInterval) {
            config.setKeepAliveInterval(keepAliveInterval);
            return this;
        }

        public Builder keepAliveIntervalMillis(long keepAliveIntervalMillis) {
            config.setKeepAliveIntervalMillis(keepAliveIntervalMillis);
            return this;
        }

        public Builder keepAliveTimeout(Duration keepAliveTimeout) {
            config.setKeepAliveTimeout(keepAliveTimeout);
            return this;
        }

        public Builder keepAliveTimeoutMillis(long keepAliveTimeoutMillis) {
            config.setKeepAliveTimeoutMillis(keepAliveTimeoutMillis);
            return this;
        }

        public Builder maxFrameSize(int maxFrameSize) {
            config.setMaxFrameSize(maxFrameSize);
            return this;
        }

        public Builder maxReceiveBuffer(int maxReceiveBuffer) {
            config.setMaxReceiveBuffer(maxReceiveBuffer);
            return this;
        }

        public Builder maxStreamBuffer(int maxStreamBuffer) {
            config.setMaxStreamBuffer(maxStreamBuffer);
            return this;
        }

        public Config build() {
            return config.copy();
        }

        public Config buildValidated() {
            return build().validate();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Config)) {
            return false;
        }
        Config that = (Config) other;
        return version == that.version
                && keepAliveDisabled == that.keepAliveDisabled
                && maxFrameSize == that.maxFrameSize
                && maxReceiveBuffer == that.maxReceiveBuffer
                && maxStreamBuffer == that.maxStreamBuffer
                && keepAliveInterval.equals(that.keepAliveInterval)
                && keepAliveTimeout.equals(that.keepAliveTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                version,
                keepAliveDisabled,
                keepAliveInterval,
                keepAliveTimeout,
                maxFrameSize,
                maxReceiveBuffer,
                maxStreamBuffer
        );
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", "Config{", "}")
                .add("version=" + version)
                .add("keepAliveDisabled=" + keepAliveDisabled)
                .add("keepAliveInterval=" + keepAliveInterval)
                .add("keepAliveTimeout=" + keepAliveTimeout)
                .add("maxFrameSize=" + maxFrameSize)
                .add("maxReceiveBuffer=" + maxReceiveBuffer)
                .add("maxStreamBuffer=" + maxStreamBuffer)
                .toString();
    }
}
