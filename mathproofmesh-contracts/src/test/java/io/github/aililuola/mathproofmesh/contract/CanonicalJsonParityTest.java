package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CanonicalJsonParityTest {
  @Test
  void matchesEveryAuthoritativeHashVector() throws IOException, ClassNotFoundException {
    Path vectors = projectRoot().resolve("migration/baseline/hash-vectors.jsonl");
    int checked = 0;
    for (String line : Files.readAllLines(vectors, StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode vector = ContractObjectMapper.parseTree(line);
      JsonNode input = vector.get("input");
      assertEquals(vector.get("canonical_json").textValue(), CanonicalJson.canonicalize(input));
      if ("pydantic_model".equals(vector.get("kind").textValue())) {
        assertModelVector(vector);
      } else if ("raw_utf8_string".equals(vector.get("hash_input_mode").textValue())) {
        assertEquals(
            vector.get("stable_hash").textValue(),
            CanonicalJson.stableHash(input.textValue()));
      } else {
        assertEquals(vector.get("stable_hash").textValue(), CanonicalJson.stableHash(input));
      }
      checked++;
    }
    assertEquals(16, checked);
  }

  private static void assertModelVector(JsonNode vector) throws ClassNotFoundException {
    String modelName = vector.get("model").textValue();
    String simpleName = modelName.substring(modelName.lastIndexOf('.') + 1);
    Class<?> type =
        Class.forName("io.github.aililuola.mathproofmesh.contract." + simpleName);
    Object contract = ContractObjectMapper.read(vector.get("input"), type);
    assertEquals(vector.get("canonical_json").textValue(), CanonicalJson.canonicalize(contract));
    assertEquals(vector.get("stable_hash").textValue(), CanonicalJson.stableHash(contract));
    if (contract instanceof MessageEnvelope message) {
      assertEquals(vector.get("content_hash").textValue(), message.contentHash());
      assertEquals(vector.get("semantic_hash").textValue(), message.expectedSemanticHash());
    } else if (contract instanceof ClaimCard claim) {
      assertEquals(vector.get("content_hash").textValue(), claim.contentHash());
    } else if (contract instanceof ProofCheckpoint checkpoint) {
      assertEquals(vector.get("content_hash").textValue(), checkpoint.contentHash());
    } else if (contract instanceof ProblemContract problem) {
      assertEquals(vector.get("content_hash").textValue(), problem.integrityHash());
      assertEquals(vector.get("goal_hash").textValue(), problem.goalHash());
    }
  }

  private static Path projectRoot() {
    return Path.of(System.getProperty("mathproofmesh.projectRoot"));
  }
}
