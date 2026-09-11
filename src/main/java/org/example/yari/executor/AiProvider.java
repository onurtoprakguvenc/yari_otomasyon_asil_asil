package org.example.yari.executor;

/**
 * Abstract interface for AI provider backends.
 * Implementations must handle rate-limit detection and throw
 * {@link RateLimitException} on quota exhaustion.
 */
public interface AiProvider {

    /**
     * Sends a prompt to the AI provider and returns the raw text response.
     *
     * @param prompt  the fully resolved prompt
     * @param modelId the model identifier to use
     * @return raw text response from the model
     * @throws RateLimitException if the provider returns a rate-limit error
     * @throws ProviderException  for all other API errors
     */
    String execute(String prompt, String modelId) throws RateLimitException, ProviderException;

    /**
     * Thrown when the provider signals rate-limit exhaustion (HTTP 429, etc.).
     */
    class RateLimitException extends Exception {
        private final long retryAfterMs;

        public RateLimitException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() { return retryAfterMs; }
    }

    /**
     * Thrown for non-rate-limit API errors.
     */
    class ProviderException extends Exception {
        private final int statusCode;

        public ProviderException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public ProviderException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        public int getStatusCode() { return statusCode; }
    }
}
