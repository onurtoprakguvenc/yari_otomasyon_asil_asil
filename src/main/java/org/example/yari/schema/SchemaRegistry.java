package org.example.yari.schema;

import org.example.yari.model.OutputSchemaTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for output schema templates.
 * Schemas must be registered before any task referencing them is queued.
 */
public final class SchemaRegistry {

    private static final SchemaRegistry INSTANCE = new SchemaRegistry();
    private final Map<String, OutputSchemaTemplate> schemas = new ConcurrentHashMap<>();

    private SchemaRegistry() {}

    public static SchemaRegistry getInstance() {
        return INSTANCE;
    }

    public void register(OutputSchemaTemplate template) {
        if (template == null || template.getId() == null) {
            throw new IllegalArgumentException("Schema template and its ID must not be null.");
        }
        schemas.put(template.getId(), template);
    }

    public Optional<OutputSchemaTemplate> get(String schemaId) {
        return Optional.ofNullable(schemas.get(schemaId));
    }

    public boolean has(String schemaId) {
        return schemas.containsKey(schemaId);
    }

    public void remove(String schemaId) {
        schemas.remove(schemaId);
    }

    public void clear() {
        schemas.clear();
    }

    public int size() {
        return schemas.size();
    }
}
