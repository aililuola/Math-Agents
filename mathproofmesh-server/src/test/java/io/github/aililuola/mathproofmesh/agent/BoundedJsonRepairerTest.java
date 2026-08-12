package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.StructuredPayloadNormalizer;
import io.github.aililuola.mathproofmesh.contract.ToolAuditReport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

final class BoundedJsonRepairerTest {
  @Test
  void escapesAnInvalidSetDifferenceBackslashWithoutChangingDecodedText() {
    String malformed = "{\"statement\":\"D = Z \\ C\",\"count\":1}";

    String repaired = new BoundedJsonRepairer(4_096).repair(malformed);
    JsonNode parsed = ContractObjectMapper.parseTree(repaired);

    assertThat(parsed.path("statement").asText()).isEqualTo("D = Z \\ C");
    assertThat(parsed.path("count").asInt()).isEqualTo(1);
  }

  @Test
  void preservesValidJsonAndEscapedMathematicalBackslashes() {
    String valid = "{\"statement\":\"x \\\\in A\",\"line\":\"a\\nb\"}";

    String repaired = new BoundedJsonRepairer(4_096).repair(valid);
    JsonNode parsed = ContractObjectMapper.parseTree(repaired);

    assertThat(parsed.path("statement").asText()).isEqualTo("x \\in A");
    assertThat(parsed.path("line").asText()).isEqualTo("a\nb");
  }

  @Test
  void escapesRawControlCharactersInsideAString() {
    String malformed = "{\"statement\":\"first\nsecond\"}";

    String repaired = new BoundedJsonRepairer(4_096).repair(malformed);

    assertThat(ContractObjectMapper.parseTree(repaired).path("statement").asText())
        .isEqualTo("first\nsecond");
  }

  @Test
  @EnabledIfEnvironmentVariable(
      named = "MATHPROOFMESH_STRATEGY_RESPONSE_REPLAY",
      matches = ".+")
  void replaysACapturedStrategyResponseThroughTheProductionContract() throws Exception {
    Path response = Path.of(System.getenv("MATHPROOFMESH_STRATEGY_RESPONSE_REPLAY"));
    JsonNode envelope = ContractObjectMapper.parseTree(Files.readString(response));
    String raw = envelope.path("text").asText();
    String repaired = new BoundedJsonRepairer(Math.max(2, raw.length())).repair(raw);
    ObjectNode payload = (ObjectNode) ContractObjectMapper.parseTree(repaired);

    StructuredPayloadNormalizer.stripServerOwnedHashes(payload);
    StructuredPayloadNormalizer.normalize(payload, StrategySet.class);
    StrategySet strategies =
        ContractObjectMapper.read(ContractObjectMapper.write(payload), StrategySet.class);

    assertThat(strategies.strategies()).isNotEmpty();
  }

  @Test
  @EnabledIfEnvironmentVariable(
      named = "MATHPROOFMESH_EXPLORATION_RESPONSE_REPLAY",
      matches = ".+")
  void replaysACapturedExplorationResponseThroughTheProductionContract() throws Exception {
    Path response = Path.of(System.getenv("MATHPROOFMESH_EXPLORATION_RESPONSE_REPLAY"));
    JsonNode envelope = ContractObjectMapper.parseTree(Files.readString(response));
    String raw = envelope.path("text").asText();
    ObjectNode payload =
        (ObjectNode)
            ContractObjectMapper.parseTree(JsonObjectExtractor.firstBalancedObject(raw));

    StructuredPayloadNormalizer.stripServerOwnedHashes(payload);
    StructuredPayloadNormalizer.normalize(payload, InitialExplorationTurn.class);
    InitialExplorationTurn turn =
        ContractObjectMapper.read(
            ContractObjectMapper.write(payload), InitialExplorationTurn.class);

    assertThat(turn.action()).isNotNull();
  }

  @Test
  @EnabledIfEnvironmentVariable(
      named = "MATHPROOFMESH_TOOL_AUDIT_RESPONSE_REPLAY",
      matches = ".+")
  void replaysACapturedToolAuditResponseThroughTheProductionContract() throws Exception {
    Path response = Path.of(System.getenv("MATHPROOFMESH_TOOL_AUDIT_RESPONSE_REPLAY"));
    JsonNode envelope = ContractObjectMapper.parseTree(Files.readString(response));
    String raw = envelope.path("text").asText();
    ObjectNode payload =
        (ObjectNode)
            ContractObjectMapper.parseTree(JsonObjectExtractor.firstBalancedObject(raw));

    StructuredPayloadNormalizer.stripServerOwnedHashes(payload);
    StructuredPayloadNormalizer.normalize(payload, ToolAuditReport.class);
    ToolAuditReport report =
        ContractObjectMapper.read(ContractObjectMapper.write(payload), ToolAuditReport.class);

    assertThat(report.verdict()).isIn("pass", "fail", "inconclusive");
  }
}
