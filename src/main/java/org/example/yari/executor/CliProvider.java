package org.example.yari.executor;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * AI provider that delegates to a CLI tool (e.g., claude, llm, ollama).
 * The prompt is passed via stdin to the configured command.
 */
public class CliProvider implements AiProvider {

    private final String command;

    public CliProvider(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("CLI command must not be blank.");
        }
        this.command = command;
    }

    @Override
    public String execute(String prompt, String modelId) throws RateLimitException, ProviderException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Write prompt to stdin
            try (OutputStream os = process.getOutputStream()) {
                os.write(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ProviderException("CLI command timed out after 120 seconds", -1);
            }

            String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                // Check for rate-limit indicators in stderr
                if (stderr.toLowerCase().contains("rate limit") || stderr.contains("429")) {
                    throw new RateLimitException("CLI rate limit: " + stderr, 5000L);
                }
                throw new ProviderException(
                        String.format("CLI exited with code %d: %s", exitCode, stderr), exitCode);
            }

            return stdout;

        } catch (IOException e) {
            throw new ProviderException("Failed to execute CLI command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("CLI execution interrupted", e);
        }
    }
}
