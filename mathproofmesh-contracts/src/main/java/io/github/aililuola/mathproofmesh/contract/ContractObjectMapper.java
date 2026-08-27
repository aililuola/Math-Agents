package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;

public final class ContractObjectMapper {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private ContractObjectMapper() {}

  public static <T> T read(String json, Class<T> contractType) {
    return read(parseTree(json), contractType);
  }

  public static <T> T read(JsonNode node, Class<T> contractType) {
    rejectExplicitNulls(node, contractType);
    try {
      T value = MAPPER.treeToValue(node, contractType);
      ContractInvariants.validateRecursively(value);
      return value;
    } catch (JsonProcessingException exception) {
      throw new ContractValidationException(
          "invalid " + contractType.getSimpleName() + " payload: " + exception.getOriginalMessage(),
          exception);
    }
  }

  public static JsonNode parseTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException exception) {
      throw new ContractValidationException("invalid JSON: " + exception.getOriginalMessage(), exception);
    }
  }

  public static JsonNode toTree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new ContractValidationException("could not serialize contract", exception);
    }
  }

  private static void rejectExplicitNulls(JsonNode node, Type targetType) {
    if (node == null || node.isNull()) {
      return;
    }
    if (targetType instanceof ParameterizedType parameterized) {
      Type raw = parameterized.getRawType();
      if (raw == java.util.List.class && node.isArray()) {
        Type elementType = parameterized.getActualTypeArguments()[0];
        node.forEach(item -> rejectExplicitNulls(item, elementType));
      } else if (raw == java.util.Map.class && node.isObject()) {
        Type valueType = parameterized.getActualTypeArguments()[1];
        node.properties().forEach(entry -> rejectExplicitNulls(entry.getValue(), valueType));
      }
      return;
    }
    if (!(targetType instanceof Class<?> targetClass)
        || !targetClass.isRecord()
        || !StrictContract.class.isAssignableFrom(targetClass)
        || !node.isObject()) {
      return;
    }
    for (RecordComponent component : targetClass.getRecordComponents()) {
      if (component.isAnnotationPresent(JsonIgnore.class)) {
        continue;
      }
      JsonProperty property = component.getAnnotation(JsonProperty.class);
      String name =
          property == null || property.value().isEmpty() ? component.getName() : property.value();
      if (!node.has(name)) {
        continue;
      }
      JsonNode fieldValue = node.get(name);
      if (fieldValue.isNull() && component.isAnnotationPresent(ContractNonNull.class)) {
        throw new ContractValidationException(name + " cannot be null");
      }
      rejectExplicitNulls(fieldValue, component.getGenericType());
    }
  }
}
