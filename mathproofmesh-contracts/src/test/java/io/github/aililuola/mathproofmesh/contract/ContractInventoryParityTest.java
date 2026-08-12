package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ContractInventoryParityTest {
  private static final String CONTRACT_PACKAGE =
      "io.github.aililuola.mathproofmesh.contract.";

  @Test
  void allPythonSchemaTypesHaveStrongJavaCounterparts()
      throws IOException, ClassNotFoundException {
    JsonNode index = readJson("migration/baseline/schemas/index.json");
    List<Class<?>> modelTypes = new ArrayList<>();
    for (JsonNode row : index) {
      String qualifiedName = row.get("qualified_name").textValue();
      if (!qualifiedName.startsWith("mathproofmesh.schemas.")
          || qualifiedName.endsWith(".StrictModel")) {
        continue;
      }
      String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
      Class<?> type = Class.forName(CONTRACT_PACKAGE + simpleName);
      assertTrue(type.isRecord(), simpleName + " must be an immutable Java record");
      assertTrue(
          StrictContract.class.isAssignableFrom(type),
          simpleName + " must implement StrictContract");
      JsonNode schemaDocument =
          readJson("migration/baseline/schemas/" + row.get("file").textValue());
      JsonNode schema = schemaDocument.get("schema");
      assertFalse(schema.get("additionalProperties").booleanValue());
      assertThrows(
          ContractValidationException.class,
          () -> ContractObjectMapper.read("{\"phase_02_unknown\":true}", type));
      if (!schema.path("required").isEmpty()) {
        assertThrows(
            ContractValidationException.class,
            () -> ContractObjectMapper.read("{}", type));
      }
      modelTypes.add(type);
    }
    assertEquals(102, modelTypes.size());
  }

  @Test
  void everyEnumNameAndWireLiteralMatchesThePythonSnapshot()
      throws IOException, ReflectiveOperationException {
    JsonNode rows = readJson("migration/baseline/enum-literals.json");
    int enumCount = 0;
    for (JsonNode row : rows) {
      String qualifiedName = row.get("qualified_name").textValue();
      if (!qualifiedName.startsWith("mathproofmesh.schemas.")) {
        continue;
      }
      String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
      Class<?> type = Class.forName(CONTRACT_PACKAGE + simpleName);
      assertTrue(type.isEnum());
      Object[] constants = type.getEnumConstants();
      JsonNode members = row.get("members");
      assertEquals(members.size(), constants.length, simpleName);
      for (int index = 0; index < constants.length; index++) {
        Enum<?> constant = (Enum<?>) constants[index];
        assertEquals(members.get(index).get("name").textValue(), constant.name());
        assertEquals(
            members.get(index).get("value").textValue(),
            type.getMethod("value").invoke(constant));
      }
      enumCount++;
    }
    assertEquals(40, enumCount);
  }

  @Test
  void contractsStayFrameworkFreeAndAvoidUntypedCoreMaps() throws IOException {
    Path sources =
        projectRoot()
            .resolve(
                "mathproofmesh-contracts/src/main/java/io/github/aililuola/mathproofmesh/contract");
    Pattern untypedMap = Pattern.compile("Map\\s*<\\s*String\\s*,\\s*Object\\s*>");
    try (var files = Files.walk(sources)) {
      for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertFalse(text.contains("org.springframework"), source.toString());
        assertFalse(untypedMap.matcher(text).find(), source.toString());
      }
    }
  }

  @Test
  void generatedInventoryIsCompleteAndBoundToAuthoritativeZip() throws IOException {
    JsonNode inventory = readJson("migration/phase-02-contract-inventory.json");
    assertEquals(
        "5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2",
        inventory.get("authoritative_zip_sha256").textValue());
    assertEquals(40, inventory.get("enums").intValue());
    assertEquals(102, inventory.get("models").intValue());
    assertEquals(142, inventory.get("total_contract_types").intValue());
  }

  private static JsonNode readJson(String relativePath) throws IOException {
    return ContractObjectMapper.parseTree(
        Files.readString(projectRoot().resolve(relativePath), StandardCharsets.UTF_8));
  }

  private static Path projectRoot() {
    return Path.of(System.getProperty("mathproofmesh.projectRoot"));
  }
}
