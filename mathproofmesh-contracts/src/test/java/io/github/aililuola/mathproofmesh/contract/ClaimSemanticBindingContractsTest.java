package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimSemanticBindingContractsTest {
  private static final String PROBLEM_HASH = "a".repeat(64);
  private static final String STATEMENT_HASH = "b".repeat(64);
  private static final String SEMANTIC_HASH = "c".repeat(64);

  @Test
  void semanticBindingsAreImmutableAndValidatePolarity() {
    ObjectNode domains = JsonNodeFactory.instance.objectNode().put("x", "D");
    ClaimEvidenceSemanticBinding evidence = evidenceBinding(domains);
    ClaimEvidenceSemanticBinding empty =
        new ClaimEvidenceSemanticBinding(
            PROBLEM_HASH,
            "claim-empty",
            STATEMENT_HASH,
            SEMANTIC_HASH,
            "Q",
            "Q",
            null,
            null,
            null,
            null,
            "negative",
            null,
            null);
    ClaimSemanticContextBinding context =
        new ClaimSemanticContextBinding(
            " claim-local ", " @claim ", null, null, null, null, "positive");

    domains.put("late", true);
    ObjectNode returnedDomains = evidence.computationDomains();
    returnedDomains.put("other", true);

    assertEquals("D", evidence.computationDomains().path("x").asText());
    assertFalse(evidence.computationDomains().has("late"));
    assertFalse(evidence.computationDomains().has("other"));
    assertTrue(empty.assumptions().isEmpty());
    assertTrue(empty.computationDomains().isEmpty());
    assertEquals("claim-local", context.claimId());
    assertEquals("@claim", context.claimBlueprintNodeId());
    assertTrue(context.localAssumptions().isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> evidence.scopeLimitations().add("late"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ClaimSemanticContextBinding(
                "claim-local", null, List.of(), List.of(), List.of(), List.of(), "unknown"));
  }

  @Test
  void computationContractsBindExactClaimIdentityAndRejectPartialBindings() {
    ClaimEvidenceSemanticBinding binding =
        evidenceBinding(JsonNodeFactory.instance.objectNode().put("x", "D"));
    ExperimentSpec bound = experimentSpec(binding);
    ExperimentSpec legacy = legacyExperimentSpec();

    assertEquals(binding.claimId(), bound.targetClaimId());
    assertEquals(binding, bound.claimEvidenceSemanticBinding());
    assertNotEquals(legacy.requestHash(), bound.requestHash());
    assertEquals(binding, ContractObjectMapper.read(ContractObjectMapper.write(bound), ExperimentSpec.class)
        .claimEvidenceSemanticBinding());
    assertThrows(
        ContractValidationException.class,
        () -> experimentSpec(binding, "different-claim", binding.assumptions(), binding.computationDomains()));
    assertThrows(
        ContractValidationException.class,
        () -> experimentSpec(binding, binding.claimId(), List.of("different-H"), binding.computationDomains()));
    assertThrows(
        ContractValidationException.class,
        () ->
            experimentSpec(
                binding,
                binding.claimId(),
                binding.assumptions(),
                JsonNodeFactory.instance.objectNode().put("x", "E")));

    ExperimentResult result = experimentResult(bound, binding, binding.claimId(), binding);
    ExperimentResult legacyResult = experimentResult(legacy, binding, binding.claimId(), null);
    assertEquals(binding, result.claimEvidenceSemanticBinding());
    assertNotEquals(legacyResult.resultHash(), result.resultHash());
    assertThrows(
        ContractValidationException.class,
        () -> experimentResult(bound, binding, "different-claim", binding));
  }

  @Test
  void claimBoundMessagesHashFullContextAndRejectIncompleteMetadata() {
    MessageEnvelope bound = claimBoundMessage("claim-bound", "positive", SEMANTIC_HASH);
    MessageEnvelope otherScope =
        new MessageEnvelope(
            bound.artifactRefs(),
            bound.assumptions(),
            bound.conclusion(),
            "",
            bound.createdAt(),
            bound.dependencies(),
            bound.dependencyRefs(),
            bound.evidenceType(),
            bound.memoryTier(),
            "claim-other-scope",
            bound.messageType(),
            bound.normalizationConfidence(),
            bound.normalizedStatement(),
            bound.problemHash(),
            bound.quantifiers(),
            bound.rawSourceRef(),
            bound.roundCreated(),
            bound.schemaVersion(),
            List.of("scope-E"),
            bound.sourceAgentId(),
            bound.sourceRole(),
            bound.sourceRouteId(),
            bound.statement(),
            bound.targetRouteIds(),
            bound.ttlRounds(),
            bound.variableBindings(),
            bound.verificationConfidence(),
            bound.verificationStatus(),
            bound.claimStatementHash(),
            bound.claimSemanticHash(),
            bound.polarity());

    assertEquals(SEMANTIC_HASH, bound.expectedSemanticHash());
    assertEquals(bound.expectedContentHash(), bound.contentHash());
    assertEquals(SEMANTIC_HASH, bound.immutablePayload().path("claim_semantic_hash").asText());
    assertNotEquals(bound.contentHash(), otherScope.contentHash());
    assertEquals(
        bound,
        ContractObjectMapper.read(ContractObjectMapper.write(bound), MessageEnvelope.class));
    assertThrows(
        ContractValidationException.class,
        () -> claimBoundMessage("partial", null, null, STATEMENT_HASH));
    assertThrows(
        ContractValidationException.class,
        () -> claimBoundMessage("invalid-polarity", "unknown", SEMANTIC_HASH));
  }

  @Test
  void proofAttemptManifestRejectsUnknownDuplicateAndLegacyBindings() {
    ClaimCard claim = claim("claim-local");
    ClaimSemanticContextBinding binding = contextBinding("claim-local");
    ProofAttempt modern = attempt(List.of(claim), List.of(binding), 1);

    assertEquals(1, modern.claimSemanticContextManifestVersion());
    assertEquals(List.of(binding), modern.claimSemanticContextBindings());
    assertThrows(
        ContractValidationException.class,
        () -> attempt(List.of(claim), List.of(binding), null));
    assertThrows(
        ContractValidationException.class,
        () -> attempt(List.of(claim), List.of(binding), 0));
    assertThrows(
        ContractValidationException.class,
        () -> attempt(List.of(claim), List.of(binding), 2));
    assertThrows(
        ContractValidationException.class,
        () -> attempt(List.of(claim), List.of(binding, binding), 1));
    assertThrows(
        ContractValidationException.class,
        () -> attempt(List.of(claim), List.of(contextBinding("unknown-claim")), 1));
  }

  private static ClaimEvidenceSemanticBinding evidenceBinding(ObjectNode domains) {
    return new ClaimEvidenceSemanticBinding(
        PROBLEM_HASH,
        "claim-bound",
        STATEMENT_HASH,
        SEMANTIC_HASH,
        "P(x)",
        "P(x)",
        List.of("H"),
        List.of(new QuantifierSpec("x", "D", "forall", 0, List.of(), "x")),
        List.of(new VariableBinding(List.of("x"), "x", "D", "claim", "x")),
        List.of("scope-D"),
        "positive",
        List.of("dependency-1"),
        domains);
  }

  private static ClaimSemanticContextBinding contextBinding(String claimId) {
    return new ClaimSemanticContextBinding(
        claimId, "@claim", List.of("H"), List.of(), List.of(), List.of("scope-D"), "positive");
  }

  private static ExperimentSpec experimentSpec(ClaimEvidenceSemanticBinding binding) {
    return experimentSpec(
        binding, binding.claimId(), binding.assumptions(), binding.computationDomains());
  }

  private static ExperimentSpec experimentSpec(
      ClaimEvidenceSemanticBinding binding,
      String targetClaimId,
      List<String> assumptions,
      ObjectNode domains) {
    return new ExperimentSpec(
        JsonNodeFactory.instance.objectNode(),
        assumptions,
        false,
        "Use exact evidence.",
        "Keep the Claim open.",
        domains,
        true,
        null,
        "experiment-bound",
        10,
        ComputationMethod.NUMBER_THEORY_CHECK,
        "Use a proof.",
        null,
        "route-a",
        ComputationPurpose.CHECK_DERIVED_IDENTITY,
        "Check the exact Claim.",
        null,
        "server",
        JsonNodeFactory.instance.objectNode(),
        7,
        binding.statement(),
        null,
        "Authorize only exact evidence.",
        targetClaimId,
        binding);
  }

  private static ExperimentSpec legacyExperimentSpec() {
    ClaimEvidenceSemanticBinding binding =
        evidenceBinding(JsonNodeFactory.instance.objectNode().put("x", "D"));
    return new ExperimentSpec(
        JsonNodeFactory.instance.objectNode(),
        binding.assumptions(),
        false,
        "Use exact evidence.",
        "Keep the Claim open.",
        binding.computationDomains(),
        true,
        null,
        "experiment-legacy",
        10,
        ComputationMethod.NUMBER_THEORY_CHECK,
        "Use a proof.",
        null,
        "route-a",
        ComputationPurpose.CHECK_DERIVED_IDENTITY,
        "Check the exact Claim.",
        null,
        "server",
        JsonNodeFactory.instance.objectNode(),
        7,
        binding.statement(),
        null,
        "Authorize only exact evidence.");
  }

  private static ExperimentResult experimentResult(
      ExperimentSpec spec,
      ClaimEvidenceSemanticBinding binding,
      String targetClaimId,
      ClaimEvidenceSemanticBinding resultBinding) {
    ObjectNode scope = JsonNodeFactory.instance.objectNode();
    scope.set("domains", binding.computationDomains());
    return new ExperimentResult(
        List.of(),
        false,
        10,
        null,
        null,
        null,
        null,
        EvidenceStrength.BOUNDED_EVIDENCE,
        true,
        spec.experimentId(),
        true,
        spec.method(),
        ExperimentOutcome.NOT_REFUTED,
        null,
        spec.pathId(),
        null,
        spec.requestHash(),
        null,
        0.01d,
        scope,
        binding.statement(),
        targetClaimId,
        "number-theory-check",
        "test-v1",
        List.of("replayed"),
        resultBinding);
  }

  private static MessageEnvelope claimBoundMessage(
      String messageId, String polarity, String semanticHash) {
    return claimBoundMessage(messageId, polarity, semanticHash, STATEMENT_HASH);
  }

  private static MessageEnvelope claimBoundMessage(
      String messageId, String polarity, String semanticHash, String statementHash) {
    return new MessageEnvelope(
        List.of("artifact://fact"),
        List.of("H"),
        "P(x)",
        "",
        null,
        List.of("dependency-1"),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        messageId,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        "p(x)",
        PROBLEM_HASH,
        List.of(new QuantifierSpec("x", "D", "forall", 0, List.of(), "x")),
        "artifact://fact",
        1,
        "1",
        List.of("scope-D"),
        "author",
        RouteRole.PROVER,
        "route-a",
        "P(x)",
        List.of("route-b"),
        2,
        List.of(new VariableBinding(List.of("x"), "x", "D", "claim", "x")),
        1.0d,
        ClaimStatus.VERIFIED,
        statementHash,
        semanticHash,
        polarity);
  }

  private static ClaimCard claim(String claimId) {
    return new ClaimCard(
        List.of(),
        claimId,
        "P",
        null,
        "none",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.5d,
        "author",
        "attempt",
        null,
        "P",
        ClaimStatus.PROPOSED,
        List.of(),
        null);
  }

  private static ProofAttempt attempt(
      List<ClaimCard> claims,
      List<ClaimSemanticContextBinding> bindings,
      Integer manifestVersion) {
    return new ProofAttempt(
        "author",
        "attempt",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        null,
        "route-a",
        PROBLEM_HASH,
        "",
        List.of(),
        claims,
        null,
        null,
        1,
        0,
        0.5d,
        AttemptStatus.PARTIAL,
        "strategy-a",
        List.of(),
        new UsageRecord(),
        bindings,
        manifestVersion);
  }
}
