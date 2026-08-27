package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class StrictYamlConfigLoader {
  private final ObjectMapper mapper;

  public StrictYamlConfigLoader() {
    YAMLFactory factory =
        YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    mapper =
        JsonMapper.builder(factory)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
  }

  public SystemConfig load(Path path) {
    Objects.requireNonNull(path, "path");
    if (!Files.isRegularFile(path)) {
      throw new ConfigValidationException("configuration file does not exist");
    }
    try (InputStream input = Files.newInputStream(path)) {
      return read(input);
    } catch (IOException exception) {
      throw new ConfigValidationException("configuration file could not be read", exception);
    }
  }

  public SystemConfig read(String yaml) {
    Objects.requireNonNull(yaml, "yaml");
    try {
      return readTree(mapper.readTree(yaml));
    } catch (JsonProcessingException exception) {
      throw sanitizedParseFailure(exception);
    }
  }

  public JsonNode normalizedTree(SystemConfig config) {
    return mapper.valueToTree(Objects.requireNonNull(config, "config"));
  }

  public ObjectNode redactedTree(SystemConfig config) {
    return Objects.requireNonNull(config, "config").redactedTree(mapper);
  }

  <T extends ConfigModel> T bindValue(JsonNode node, Class<T> type) {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(type, "type");
    ConfigShapeValidator.validateValue(node, type);
    try {
      return mapper.treeToValue(node, type);
    } catch (JsonProcessingException exception) {
      throw sanitizedBindingFailure(exception);
    }
  }

  private SystemConfig read(InputStream input) {
    try {
      return readTree(mapper.readTree(input));
    } catch (JsonProcessingException exception) {
      throw sanitizedParseFailure(exception);
    } catch (IOException exception) {
      throw new ConfigValidationException("configuration stream could not be read", exception);
    }
  }

  private SystemConfig readTree(JsonNode raw) {
    if (raw == null || !raw.isObject()) {
      throw new ConfigValidationException("configuration root must be a mapping");
    }
    JsonNode normalized = normalizeLegacyKeys(raw);
    ConfigShapeValidator.validateSystemConfig(normalized);
    try {
      return mapper.treeToValue(normalized, SystemConfig.class);
    } catch (JsonProcessingException exception) {
      throw sanitizedBindingFailure(exception);
    }
  }

  private static JsonNode normalizeLegacyKeys(JsonNode raw) {
    ObjectNode normalized = ((ObjectNode) raw).deepCopy();
    JsonNode runtimeNode = normalized.get("runtime");
    if (runtimeNode instanceof ObjectNode runtime) {
      runtime.remove("reasoning_only_abort_seconds");
      runtime.remove("reasoning_only_min_characters");
    }
    JsonNode policyNode = normalized.get("deep_exploration_policy");
    if (policyNode instanceof ObjectNode policy) {
      JsonNode tiersNode = policy.get("tiers");
      if (tiersNode instanceof ArrayNode tiers) {
        for (JsonNode tierNode : tiers) {
          if (tierNode instanceof ObjectNode tier) {
            tier.remove("no_content_timeout_seconds");
            tier.remove("wall_timeout_seconds");
            JsonNode legacyReserve = tier.remove("answer_reserve_tokens");
            if (legacyReserve != null && !tier.has("artifact_recovery_tokens")) {
              tier.set("artifact_recovery_tokens", legacyReserve);
            }
          }
        }
      }
    }
    return normalized;
  }

  private static ConfigValidationException sanitizedParseFailure(
      JsonProcessingException exception) {
    return new ConfigValidationException(
        "configuration YAML is malformed or contains a duplicate key", exception);
  }

  private static ConfigValidationException sanitizedBindingFailure(
      JsonProcessingException exception) {
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof ConfigValidationException validation) {
        return validation;
      }
      cause = cause.getCause();
    }
    return new ConfigValidationException(
        "configuration does not match the strict schema", exception);
  }
}
