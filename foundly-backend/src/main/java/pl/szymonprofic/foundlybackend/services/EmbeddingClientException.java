package pl.szymonprofic.foundlybackend.services;

public class EmbeddingClientException extends RuntimeException {
    private final boolean retryable;

    public EmbeddingClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public EmbeddingClientException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
