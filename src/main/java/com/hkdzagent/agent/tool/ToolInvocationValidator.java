package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ToolInvocationValidator {

    private final AgentToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final int previewLimit;

    public ToolInvocationValidator(AgentToolRegistry registry, ObjectMapper objectMapper, int previewLimit) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        if (previewLimit < 16) {
            throw new IllegalArgumentException("previewLimit must be at least 16");
        }
        this.previewLimit = previewLimit;
    }

    public ValidatedToolInvocation<?, ?> validate(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        if (context == null) {
            throw new ToolInvocationValidationException("tool invocation context is required");
        }

        AgentTool<?, ?> tool;
        try {
            tool = registry.require(toolName);
        } catch (IllegalArgumentException exception) {
            throw new ToolInvocationValidationException(exception.getMessage(), exception);
        }

        JsonNode arguments = parseArguments(argumentsJson);
        JsonNode schema = parseSchema(tool.metadata());
        validateObjectSchema(arguments, schema, "$arguments");
        return createInvocation(context, tool, arguments);
    }

    private JsonNode parseArguments(String argumentsJson) {
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson == null ? "" : argumentsJson);
            if (parsed == null || !parsed.isObject()) {
                throw new ToolInvocationValidationException("tool arguments must be a valid JSON object");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new ToolInvocationValidationException("tool arguments must be valid JSON", exception);
        }
    }

    private JsonNode parseSchema(ToolMetadata metadata) {
        try {
            return objectMapper.readTree(metadata.inputSchema());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("registered tool has invalid input schema: " + metadata.name(), exception);
        }
    }

    private void validateObjectSchema(JsonNode value, JsonNode schema, String path) {
        if (!value.isObject()) {
            throw invalid(path + " must be an object");
        }

        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(field -> {
                String name = field.asText();
                if (!value.has(name) || value.get(name).isNull()) {
                    throw invalid(path + "." + name + " is required");
                }
            });
        }

        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            var fields = value.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode propertySchema = properties.get(field.getKey());
                if (propertySchema == null) {
                    if (!schema.path("additionalProperties").asBoolean(true)) {
                        throw invalid(path + "." + field.getKey() + " is not allowed");
                    }
                    continue;
                }
                validateType(field.getValue(), propertySchema.path("type").asText(), path + "." + field.getKey());
            }
        }
    }

    private void validateType(JsonNode value, String type, String path) {
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "null" -> value.isNull();
            case "" -> true;
            default -> throw new IllegalStateException("unsupported JSON schema type: " + type);
        };
        if (!valid) {
            throw invalid(path + " must be a " + type);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ValidatedToolInvocation<?, ?> createInvocation(
            ToolInvocationContext context,
            AgentTool tool,
            JsonNode arguments
    ) {
        try {
            Object input = objectMapper.treeToValue(arguments, tool.inputType());
            String canonical = objectMapper.writeValueAsString(canonicalize(arguments));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            String preview = truncate(objectMapper.writeValueAsString(sanitize(arguments)));
            return new ValidatedToolInvocation(context, tool, tool.metadata(), input, hash, preview);
        } catch (ToolInvocationValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolInvocationValidationException("tool arguments cannot be converted to "
                    + tool.inputType().getSimpleName(), exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.properties().forEach(field -> names.add(field.getKey()));
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        return node.deepCopy();
    }

    private JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            node.properties().forEach(field -> sanitized.set(
                    field.getKey(),
                    isSensitive(field.getKey()) ? TextNode.valueOf("***") : sanitize(field.getValue())
            ));
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(sanitize(item)));
            return array;
        }
        return node.deepCopy();
    }

    private boolean isSensitive(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("authorization");
    }

    private String truncate(String value) {
        return value.length() <= previewLimit ? value : value.substring(0, previewLimit) + "...";
    }

    private ToolInvocationValidationException invalid(String message) {
        return new ToolInvocationValidationException("tool arguments failed schema validation: " + message);
    }
}
