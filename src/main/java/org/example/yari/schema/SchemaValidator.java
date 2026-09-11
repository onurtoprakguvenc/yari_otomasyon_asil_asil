package org.example.yari.schema;

import com.google.gson.*;
import org.example.yari.model.OutputSchemaTemplate;
import org.example.yari.model.OutputSchemaTemplate.FieldSpec;
import org.example.yari.model.OutputSchemaTemplate.FieldType;
import org.example.yari.model.OutputSchemaTemplate.OutputFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates raw AI model output against a declared {@link OutputSchemaTemplate}.
 * Halts pipeline execution immediately if schema definitions are omitted or output is non-conformant.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /**
     * Validates the raw output string against the given schema template.
     *
     * @return a {@link ValidationResult} containing pass/fail and any errors
     */
    public static ValidationResult validate(String rawOutput, OutputSchemaTemplate schema) {
        if (schema == null) {
            return ValidationResult.failure("Schema template is null. Pipeline execution halted: " +
                    "schema definitions must not be omitted under token pressure.");
        }
        if (rawOutput == null || rawOutput.isBlank()) {
            return ValidationResult.failure("Raw output is empty or null.");
        }

        return switch (schema.getFormat()) {
            case JSON -> validateJson(rawOutput, schema);
            case MARKDOWN -> validateMarkdown(rawOutput, schema);
            case PLAIN_TEXT -> validatePlainText(rawOutput, schema);
            case CODE -> validateCode(rawOutput, schema);
        };
    }

    private static ValidationResult validateJson(String rawOutput, OutputSchemaTemplate schema) {
        List<String> errors = new ArrayList<>();

        // Strip markdown code fences if present
        String cleaned = rawOutput.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.strip();

        JsonObject jsonObj;
        try {
            JsonElement element = JsonParser.parseString(cleaned);
            if (!element.isJsonObject()) {
                return ValidationResult.failure("Expected a JSON object at the root level, got: " + element.getClass().getSimpleName());
            }
            jsonObj = element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return ValidationResult.failure("JSON parse error: " + e.getMessage());
        }

        for (FieldSpec field : schema.getRequiredFields()) {
            if (!jsonObj.has(field.getName())) {
                if (field.isRequired()) {
                    errors.add("Missing required field: " + field.getName());
                }
                continue;
            }

            JsonElement value = jsonObj.get(field.getName());
            if (!checkFieldType(value, field.getType())) {
                errors.add(String.format("Field '%s' expected type %s but got %s",
                        field.getName(), field.getType(), describeJsonType(value)));
            }

            if (field.getRegex() != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                if (!Pattern.matches(field.getRegex(), value.getAsString())) {
                    errors.add(String.format("Field '%s' does not match pattern: %s", field.getName(), field.getRegex()));
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.success(cleaned) : ValidationResult.failure(errors);
    }

    private static ValidationResult validateMarkdown(String rawOutput, OutputSchemaTemplate schema) {
        List<String> errors = new ArrayList<>();
        for (FieldSpec field : schema.getRequiredFields()) {
            if (field.isRequired()) {
                // For markdown, required fields correspond to expected section headers
                String headerPattern = "(?m)^#{1,6}\\s+" + Pattern.quote(field.getName());
                if (!Pattern.compile(headerPattern, Pattern.CASE_INSENSITIVE).matcher(rawOutput).find()) {
                    errors.add("Missing required section header: " + field.getName());
                }
            }
        }
        return errors.isEmpty() ? ValidationResult.success(rawOutput) : ValidationResult.failure(errors);
    }

    private static ValidationResult validatePlainText(String rawOutput, OutputSchemaTemplate schema) {
        if (rawOutput.isBlank()) {
            return ValidationResult.failure("Plain text output is empty.");
        }
        return ValidationResult.success(rawOutput);
    }

    private static ValidationResult validateCode(String rawOutput, OutputSchemaTemplate schema) {
        // Strip code fences
        String cleaned = rawOutput.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).strip();
        }
        if (cleaned.isBlank()) {
            return ValidationResult.failure("Code output is empty after stripping fences.");
        }
        return ValidationResult.success(cleaned);
    }

    private static boolean checkFieldType(JsonElement value, FieldType expected) {
        return switch (expected) {
            case STRING -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case NUMBER -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case BOOLEAN -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case ARRAY -> value.isJsonArray();
            case OBJECT -> value.isJsonObject();
        };
    }

    private static String describeJsonType(JsonElement el) {
        if (el.isJsonNull()) return "null";
        if (el.isJsonArray()) return "array";
        if (el.isJsonObject()) return "object";
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) return "string";
            if (p.isNumber()) return "number";
            if (p.isBoolean()) return "boolean";
        }
        return "unknown";
    }

    /**
     * Result of a schema validation pass.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String cleanedOutput;
        private final List<String> errors;

        private ValidationResult(boolean valid, String cleanedOutput, List<String> errors) {
            this.valid = valid;
            this.cleanedOutput = cleanedOutput;
            this.errors = errors;
        }

        public static ValidationResult success(String cleanedOutput) {
            return new ValidationResult(true, cleanedOutput, List.of());
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, null, List.of(error));
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, null, errors);
        }

        public boolean isValid() { return valid; }
        public String getCleanedOutput() { return cleanedOutput; }
        public List<String> getErrors() { return errors; }

        public String getErrorSummary() {
            return String.join("; ", errors);
        }
    }
}
