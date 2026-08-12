package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure validation and command construction for the optional Docker sandbox. */
public final class SandboxFunctions {
  private static final Set<String> REQUIRED_OUTPUT =
      Set.of("outcome", "cases_checked", "scope", "exact_arithmetic");
  private static final Map<String, String> OUTPUT_TYPES =
      Map.of(
          "outcome", "string",
          "cases_checked", "integer",
          "scope", "object",
          "exact_arithmetic", "boolean");

  private SandboxFunctions() {}

  public static List<String> buildDockerCommand(
      String dockerExecutable, SandboxSettings settings, Path workDirectory) {
    if (!settings.enabled()) {
      throw new IllegalStateException("sandboxed Python is disabled");
    }
    String executable =
        dockerExecutable == null || dockerExecutable.isBlank()
            ? "docker"
            : dockerExecutable;
    Path normalized = workDirectory.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized)) {
      throw new IllegalArgumentException("sandbox work directory does not exist");
    }
    String runner =
        "import json,runpy,sys;"
            + "ns=runpy.run_path('/work/program.py',run_name='experiment');"
            + "data=json.loads(sys.stdin.read());"
            + "result=ns['run'](data);"
            + "out=json.dumps(result,sort_keys=True,separators=(',',':'));"
            + "assert len(out)<="
            + settings.maxOutputChars()
            + ",'output limit exceeded';"
            + "sys.stdout.write(out)";
    return List.of(
        executable,
        "run",
        "--rm",
        "--interactive",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        Integer.toString(settings.pidsLimit()),
        "--memory",
        settings.memoryMb() + "m",
        "--cpus",
        Double.toString(settings.cpus()),
        "--user",
        "65532:65532",
        "--tmpfs",
        "/tmp:rw,noexec,nosuid,size=64m",
        "--mount",
        "type=bind,src=" + normalized + ",dst=/work,readonly",
        "--workdir",
        "/tmp",
        "--entrypoint",
        "python",
        settings.image(),
        "-I",
        "-B",
        "-c",
        runner);
  }

  public static void validateProgramSchemas(ExperimentProgram program) {
    ObjectNode input = program.inputSchema();
    ObjectNode inputProperties = objectField(input, "properties");
    JsonNode seed = inputProperties.get("seed");
    if (seed == null
        || !seed.isObject()
        || !"integer".equals(seed.path("type").asText())
        || !stringArray(input.get("required")).contains("seed")) {
      throw new IllegalArgumentException(
          "sandbox input_schema must require the injected integer seed");
    }
    ObjectNode output = program.outputSchema();
    ObjectNode outputProperties = objectField(output, "properties");
    Set<String> required = Set.copyOf(stringArray(output.get("required")));
    if (!required.containsAll(REQUIRED_OUTPUT)) {
      throw new IllegalArgumentException(
          "sandbox output_schema must require outcome, cases_checked, "
              + "scope, and exact_arithmetic");
    }
    OUTPUT_TYPES.forEach(
        (name, type) -> {
          JsonNode declaration = outputProperties.get(name);
          if (declaration == null
              || !declaration.isObject()
              || !type.equals(declaration.path("type").asText())) {
            throw new IllegalArgumentException(
                "sandbox output_schema must declare " + name + " as " + type);
          }
        });
  }

  public static ObjectNode validateJsonObject(
      JsonNode payload, ObjectNode schema, String label) {
    if (!(payload instanceof ObjectNode object)) {
      throw new IllegalArgumentException("sandbox " + label + " must be a JSON object");
    }
    String type = schema.path("type").asText("object");
    if (!"object".equals(type)) {
      throw new IllegalArgumentException(
          "sandbox " + label + " schema must have type=object");
    }
    validateJsonValue(payload, schema, "$");
    return object.deepCopy();
  }

  public static String findDockerExecutable(
      String discoveredOnPath, Path localAppData, Path programFiles) {
    if (discoveredOnPath != null && !discoveredOnPath.isBlank()) {
      return discoveredOnPath;
    }
    List<Path> candidates = new ArrayList<>();
    if (localAppData != null) {
      candidates.add(
          localAppData
              .resolve("Programs")
              .resolve("DockerDesktop")
              .resolve("resources")
              .resolve("bin")
              .resolve("docker.exe"));
    }
    if (programFiles != null) {
      candidates.add(
          programFiles
              .resolve("Docker")
              .resolve("Docker")
              .resolve("resources")
              .resolve("bin")
              .resolve("docker.exe"));
    }
    return candidates.stream()
        .filter(Files::isRegularFile)
        .map(Path::toString)
        .findFirst()
        .orElse(null);
  }

  private static void validateJsonValue(
      JsonNode value, ObjectNode schema, String path) {
    String type = schema.path("type").isMissingNode() ? null : schema.path("type").asText();
    if (type != null && !matchesType(value, type)) {
      throw new IllegalArgumentException("JSON value at " + path + " has the wrong type");
    }
    if (schema.has("const") && !schema.get("const").equals(value)) {
      throw new IllegalArgumentException(
          "JSON value at " + path + " does not match const");
    }
    if (schema.get("enum") instanceof ArrayNode enumeration) {
      boolean found = false;
      for (JsonNode candidate : enumeration) {
        found |= candidate.equals(value);
      }
      if (!found) {
        throw new IllegalArgumentException(
            "JSON value at " + path + " is outside enum");
      }
    }
    if (value.isObject()) {
      ObjectNode properties =
          schema.get("properties") == null
              ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
              : objectField(schema, "properties");
      for (String required : stringArray(schema.get("required"))) {
        if (!value.has(required)) {
          throw new IllegalArgumentException(
              "JSON object at " + path + " is missing " + required);
        }
      }
      if (schema.path("additionalProperties").isBoolean()
          && !schema.path("additionalProperties").booleanValue()) {
        value.fieldNames()
            .forEachRemaining(
                name -> {
                  if (!properties.has(name)) {
                    throw new IllegalArgumentException(
                        "JSON object at " + path + " has unexpected field " + name);
                  }
                });
      }
      value.properties()
          .forEach(
              entry -> {
                JsonNode declaration = properties.get(entry.getKey());
                if (declaration != null && declaration.isObject()) {
                  validateJsonValue(
                      entry.getValue(),
                      (ObjectNode) declaration,
                      path + "." + entry.getKey());
                }
              });
    } else if (value.isArray() && schema.get("items") instanceof ObjectNode items) {
      for (int index = 0; index < value.size(); index++) {
        validateJsonValue(value.get(index), items, path + "[" + index + "]");
      }
    }
  }

  private static boolean matchesType(JsonNode value, String type) {
    return switch (type) {
      case "array" -> value.isArray();
      case "boolean" -> value.isBoolean();
      case "integer" -> value.isIntegralNumber();
      case "null" -> value.isNull();
      case "number" -> value.isNumber();
      case "object" -> value.isObject();
      case "string" -> value.isTextual();
      default ->
          throw new IllegalArgumentException(
              "unsupported JSON Schema type: " + type);
    };
  }

  private static ObjectNode objectField(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(
          "JSON Schema " + field + " must be an object");
    }
    return (ObjectNode) value;
  }

  private static List<String> stringArray(JsonNode value) {
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("JSON Schema required must be an array");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isTextual()) {
        throw new IllegalArgumentException(
            "JSON Schema required entries must be strings");
      }
      result.add(item.textValue());
    }
    return List.copyOf(result);
  }
}
