package org.example.yari.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates prompt templates against dynamic intake rules before queuing.
 * Rejects malformed definitions upfront to avoid generating low-value junk outputs.
 */
public final class PromptValidator {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");
    private static final int MAX_PROMPT_LENGTH = 100_000;
    private static final int MIN_PROMPT_LENGTH = 5;

    private PromptValidator() {}

    /**
     * Validates a prompt template and its parameters.
     *
     * @param template   the prompt template string
     * @param parameters the parameter map to interpolate
     * @return list of validation errors; empty if valid
     */
    public static List<String> validate(String template, Map<String, String> parameters) {
        List<String> errors = new ArrayList<>();

        if (template == null || template.isBlank()) {
            errors.add("Prompt template must not be null or blank.");
            return errors;
        }

        if (template.length() < MIN_PROMPT_LENGTH) {
            errors.add(String.format("Prompt template is too short (%d chars, minimum %d).",
                    template.length(), MIN_PROMPT_LENGTH));
        }

        if (template.length() > MAX_PROMPT_LENGTH) {
            errors.add(String.format("Prompt template exceeds maximum length (%d chars, maximum %d).",
                    template.length(), MAX_PROMPT_LENGTH));
        }

        // Find all declared parameters in the template
        Matcher matcher = PARAM_PATTERN.matcher(template);
        List<String> declaredParams = new ArrayList<>();
        while (matcher.find()) {
            declaredParams.add(matcher.group(1));
        }

        // Check all declared parameters have values
        for (String param : declaredParams) {
            if (parameters == null || !parameters.containsKey(param)) {
                errors.add(String.format("Template references parameter '{{%s}}' but no value was provided.", param));
            }
        }

        // Check for unused provided parameters (warning, not error)
        if (parameters != null) {
            for (String key : parameters.keySet()) {
                if (!declaredParams.contains(key)) {
                    // Not an error, just informational
                }
            }
        }

        return errors;
    }
}
