package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractNonNull;
import io.github.aililuola.mathproofmesh.contract.ContractAllowedValues;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PromptJsonSchema {
  private PromptJsonSchema() {}

  public static JsonNode forType(Class<?> type) {
    return schema(type, new LinkedHashSet<>());
  }

  private static ObjectNode schema(Type type, Set<Type> visiting) {
    if (type instanceof ParameterizedType parameterized) {
      Type raw = parameterized.getRawType();
      if (raw == List.class || raw == Set.class) {
        return JsonNodeFactory.instance
            .objectNode()
            .put("type", "array")
            .set(
                "items",
                schema(
                    parameterized.getActualTypeArguments()[0],
                    visiting));
      }
      if (raw == Map.class) {
        return JsonNodeFactory.instance
            .objectNode()
            .put("type", "object");
      }
    }
    if (!(type instanceof Class<?> target)) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (target == String.class
        || target == Character.class
        || target == char.class
        || target == Path.class) {
      return JsonNodeFactory.instance.objectNode().put("type", "string");
    }
    if (target == boolean.class || target == Boolean.class) {
      return JsonNodeFactory.instance.objectNode().put("type", "boolean");
    }
    if (target.isPrimitive()
        || Number.class.isAssignableFrom(target)) {
      String kind =
          target == float.class
                  || target == double.class
                  || target == Float.class
                  || target == Double.class
              ? "number"
              : "integer";
      return JsonNodeFactory.instance.objectNode().put("type", kind);
    }
    if (target.isEnum()) {
      ObjectNode result =
          JsonNodeFactory.instance.objectNode().put("type", "string");
      ArrayNode values = result.putArray("enum");
      for (Object constant : target.getEnumConstants()) {
        JsonNode serialized = ContractObjectMapper.toTree(constant);
        values.add(
            serialized.isTextual()
                ? serialized.textValue()
                : constant.toString());
      }
      return result;
    }
    if (JsonNode.class.isAssignableFrom(target)) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (!target.isRecord() || !visiting.add(type)) {
      return JsonNodeFactory.instance.objectNode().put("type", "object");
    }
    ObjectNode result =
        JsonNodeFactory.instance
            .objectNode()
            .put("type", "object")
            .put("additionalProperties", false);
    ObjectNode properties = result.putObject("properties");
    ArrayNode required = result.putArray("required");
    for (RecordComponent component : target.getRecordComponents()) {
      if (annotation(target, component, JsonIgnore.class) != null) {
        continue;
      }
      JsonProperty property = annotation(target, component, JsonProperty.class);
      String name =
          property == null || property.value().isEmpty()
              ? component.getName()
              : property.value();
      ObjectNode componentSchema = schema(component.getGenericType(), visiting);
      ContractAllowedValues allowedValues =
          annotation(target, component, ContractAllowedValues.class);
      if (allowedValues != null) {
        ArrayNode values = componentSchema.putArray("enum");
        for (String value : allowedValues.value()) {
          values.add(value);
        }
      }
      properties.set(name, componentSchema);
      if (component.getType().isPrimitive()
          || component.isAnnotationPresent(ContractNonNull.class)
          || property != null && property.required()) {
        required.add(name);
      }
    }
    visiting.remove(type);
    return result;
  }

  private static <A extends Annotation> A annotation(
      Class<?> target, RecordComponent component, Class<A> annotationType) {
    A annotation = component.getAnnotation(annotationType);
    if (annotation != null) {
      return annotation;
    }
    annotation = component.getAccessor().getAnnotation(annotationType);
    if (annotation != null) {
      return annotation;
    }
    try {
      Field field = target.getDeclaredField(component.getName());
      return field.getAnnotation(annotationType);
    } catch (NoSuchFieldException exception) {
      return null;
    }
  }
}
