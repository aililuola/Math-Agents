package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.BlindVerificationReport;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypedPromptSerializationParityTest {
  @Test
  void jsonRecursivelyNormalizesNestedTypedValues() {
    Object normalized =
        PromptContextNormalizer.normalize(
            Map.of(
                "nested",
                Map.of("problem", new NestedContract("hash-value")),
                "role",
                RouteRole.PROVER,
                "artifact",
                new NestedArtifact(
                    "certificate", Path.of("proof/artifact.json")),
                "tags",
                Set.of("geometry", "algebra")));
    JsonNode payload =
        ContractObjectMapper.parseTree(
            ContractObjectMapper.write(normalized));

    assertThat(payload.path("nested").path("problem").path("integrity_hash").asText())
        .isEqualTo("hash-value");
    assertThat(payload.path("role").asText()).isEqualTo("prover");
    assertThat(payload.path("artifact").path("path").asText())
        .isEqualTo(Path.of("proof/artifact.json").toString());
    assertThat(payload.path("tags"))
        .extracting(JsonNode::toString)
        .containsExactly("\"algebra\"", "\"geometry\"");
  }

  @Test
  void everyV07TypedPromptConstructsWithNestedModels() {
    PromptFactory factory = new PromptFactory("English");
    NestedArtifact nested =
        new NestedArtifact(
            "item", Path.of("structured/item.json"));
    List<String> stages =
        List.of(
            "route_prove",
            "route_skeptic",
            "route_referee",
            "post_failure_bottleneck",
            "bridge_lemma",
            "resolve_contradiction",
            "acknowledge_message",
            "counterexample_search",
            "representation_switchboard",
            "structural_analogy_search",
            "invent_auxiliary_construction",
            "hypothesize_invariant",
            "reverse_goal_analysis",
            "persistent_meta_strategy",
            "surprise_exploration",
            "inspiration_referee");
    List<PromptBundle<Response>> bundles =
        stages.stream()
            .map(
                stage ->
                    factory.typedStage(
                        stage,
                        Response.class,
                        Map.of(
                            "nested",
                            Map.of(
                                "contract",
                                new NestedContract("problem-hash"),
                                "artifact_path",
                                nested.path())),
                        0.0d,
                        1024,
                        false))
            .toList();

    assertThat(bundles)
        .extracting(PromptBundle::stage)
        .doesNotHaveDuplicates()
        .containsExactlyElementsOf(stages);
    for (PromptBundle<Response> bundle : bundles) {
      JsonNode context = context(bundle);
      assertThat(
              context
                  .path("nested")
                  .path("contract")
                  .path("integrity_hash")
                  .asText())
          .isEqualTo("problem-hash");
      assertThat(
              context
                  .path("nested")
                  .path("artifact_path")
                  .asText())
          .isEqualTo(Path.of("structured/item.json").toString());
      assertThat(bundle.user()).contains("JSON SCHEMA:");
      assertThat(bundle.responseSchema().path("type").asText())
          .isEqualTo("object");
    }
  }

  @Test
  void crossFieldPromptExamplesAreActuallySchemaValid() {
    JsonNode schema = PromptJsonSchema.forType(Response.class);
    Response parsed =
        ContractObjectMapper.read(
            "{\"accepted\":true,\"answer\":\"proved\"}",
            Response.class);

    assertThat(parsed).isEqualTo(new Response(true, "proved"));
    assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    Iterable<String> fieldNames =
        () -> schema.path("properties").fieldNames();
    assertThat(fieldNames)
        .containsExactlyInAnyOrder("accepted", "answer");
    assertThat(schema.path("required"))
        .extracting(JsonNode::asText)
        .contains("accepted");
  }

  @Test
  void livePromptSchemasUseCanonicalWireNamesAndAllowedValues() {
    JsonNode triage = PromptJsonSchema.forType(TriageResult.class);
    JsonNode triageProperties = triage.path("properties");

    assertThat(triageProperties.has("key_risks")).isTrue();
    assertThat(triageProperties.has("likely_tools")).isTrue();
    assertThat(triageProperties.has("task_requirements")).isTrue();
    assertThat(triageProperties.has("keyRisks")).isFalse();
    assertThat(triageProperties.path("proof_mode").path("enum"))
        .extracting(JsonNode::asText)
        .containsExactly("direct", "decomposition", "hybrid");

    JsonNode strategies = PromptJsonSchema.forType(StrategySet.class).path("properties");
    assertThat(strategies.has("coverage_notes")).isTrue();
    assertThat(strategies.has("omitted_directions")).isTrue();
    assertThat(strategies.has("coverageNotes")).isFalse();

    JsonNode verification =
        PromptJsonSchema.forType(VerificationReport.class)
            .path("properties")
            .path("target_type")
            .path("enum");
    assertThat(verification)
        .extracting(JsonNode::asText)
        .containsExactly("attempt", "claim", "proof_delta", "checkpoint", "final_proof");

    JsonNode proofStep =
        PromptJsonSchema.forType(FinalProof.class)
            .path("properties")
            .path("proof_steps")
            .path("items")
            .path("properties")
            .path("step_type")
            .path("enum");
    assertThat(proofStep)
        .extracting(JsonNode::asText)
        .contains("derivation", "assumption_intro", "case_split", "construction");

    assertThat(
            PromptJsonSchema.forType(ToolRequest.class)
                .path("properties")
                .path("kind")
                .path("enum"))
        .extracting(JsonNode::asText)
        .contains("bounded_greedy_sequence", "candidate_period_check", "lean_check");
  }

  @Test
  void schemaGuidedTriagePayloadParsesUnderTheStrictContract() {
    String payload =
        """
        {
          "confidence": 0.9,
          "difficulty": "olympiad",
          "key_risks": ["eventual periodicity"],
          "likely_tools": ["bounded_greedy_sequence"],
          "problem_kind": "proof",
          "proof_mode": "hybrid",
          "rationale": "Combine structural and computational exploration.",
          "suggested_paths": 4,
          "suggested_rounds": 3,
          "task_requirements": ["proof"]
        }
        """;

    TriageResult result = ContractObjectMapper.read(payload, TriageResult.class);

    assertThat(result.proofMode()).isEqualTo("hybrid");
    assertThat(result.keyRisks()).containsExactly("eventual periodicity");
  }

  @Test
  void claimExtractionPromptNamesCanonicalDependencyNamespaces() {
    PromptBundle<Response> bundle =
        new PromptFactory("English")
            .typedStage(
                "claim_extraction",
                Response.class,
                Map.of("attempt", "partial proof"),
                0.0d,
                1024,
                false);

    assertThat(bundle.user())
        .contains(
            "\"local_claim\"",
            "\"local_step\"",
            "\"external_result\"",
            "Never emit the legacy kind \"external\"");
  }

  @Test
  void blindVerificationPromptsPassTheLeakageGuard() {
    PromptFactory factory = new PromptFactory("English");

    for (String stage :
        List.of(
            "blind_structural_verification",
            "blind_detailed_verification")) {
      PromptBundle<BlindVerificationReport> bundle =
          factory.typedStage(
              stage,
              BlindVerificationReport.class,
              Map.of(
                  "packet",
                  Map.of(
                      "problem", "Prove the stated proposition.",
                      "proof", "A self-contained candidate proof.")));

      PromptFactory.assertBlindPromptSafe(bundle);
      assertThat(bundle.stage()).isEqualTo(stage);
    }
  }

  private static JsonNode context(PromptBundle<?> bundle) {
    String payload =
        bundle.user().split("SANITIZED CONTEXT:\\R", 2)[1]
            .split("\\R\\ROUTPUT LANGUAGE:", 2)[0];
    return ContractObjectMapper.parseTree(payload);
  }

  public record NestedContract(
      @JsonProperty("integrity_hash") String integrityHash) {}

  public record NestedArtifact(String label, Path path) {}

  public record Response(boolean accepted, String answer) {}
}
