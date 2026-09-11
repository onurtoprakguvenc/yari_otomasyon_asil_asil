package org.example.yari.executor;

import org.example.yari.settings.YariSettings;

/**
 * Factory for creating AI provider instances based on current plugin settings.
 */
public final class ProviderFactory {

    private ProviderFactory() {}

    public static AiProvider create() {
        YariSettings.State settings = YariSettings.getInstance().getState();
        return switch (settings.apiProvider) {
            case "gemini" -> new GeminiProvider(settings.apiKey, settings.apiEndpoint);
            case "anthropic" -> new AnthropicProvider(settings.apiKey, settings.apiEndpoint);
            case "openai" -> new OpenAiProvider(settings.apiKey, settings.apiEndpoint);
            case "cli" -> new CliProvider(settings.cliCommand);
            default -> throw new IllegalArgumentException("Unknown provider: " + settings.apiProvider);
        };
    }
}
