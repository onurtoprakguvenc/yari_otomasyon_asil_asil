package org.example.yari.model;

import java.util.List;
import java.util.Map;

/**
 * Defines the expected structure for validating AI model outputs.
 * Each template declares required fields, their types, and optional constraints.
 */
public class OutputSchemaTemplate {
    private final String id;
    private final String name;
    private final String description;
    private final List<FieldSpec> requiredFields;
    private final OutputFormat format;

    public OutputSchemaTemplate(String id, String name, String description,
                                List<FieldSpec> requiredFields, OutputFormat format) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.requiredFields = requiredFields;
        this.format = format;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<FieldSpec> getRequiredFields() { return requiredFields; }
    public OutputFormat getFormat() { return format; }

    public enum OutputFormat {
        JSON, MARKDOWN, PLAIN_TEXT, CODE
    }

    public static class FieldSpec {
        private final String name;
        private final FieldType type;
        private final boolean required;
        private final String description;
        private final String regex;

        public FieldSpec(String name, FieldType type, boolean required, String description, String regex) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.description = description;
            this.regex = regex;
        }

        public String getName() { return name; }
        public FieldType getType() { return type; }
        public boolean isRequired() { return required; }
        public String getDescription() { return description; }
        public String getRegex() { return regex; }
    }

    public enum FieldType {
        STRING, NUMBER, BOOLEAN, ARRAY, OBJECT
    }
}
