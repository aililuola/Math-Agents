package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;

final class MemoryFixtures {
  static final String PROBLEM_HASH = "8".repeat(64);

  private MemoryFixtures() {}

  static MessageEnvelope fact(
      String id, String statement, String route, String author, List<String> dependencies) {
    return message(
        id,
        statement,
        statement,
        route,
        author,
        MessageType.VERIFIED_LEMMA,
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        ClaimStatus.VERIFIED,
        0.95,
        1.0,
        dependencies);
  }

  static MessageEnvelope insight(
      String id,
      String statement,
      String route,
      String author,
      EvidenceType evidenceType,
      double confidence) {
    return message(
        id,
        statement,
        statement,
        route,
        author,
        MessageType.CLAIM_PROPOSAL,
        evidenceType,
        MemoryTier.INSIGHT,
        ClaimStatus.UNCERTAIN,
        confidence,
        1.0,
        List.of());
  }

  static MessageEnvelope counterexample(
      String id, String statement, List<String> dependencies) {
    return message(
        id,
        statement,
        "a concrete witness refutes " + statement,
        "route-c",
        "counterexample-author",
        MessageType.COUNTEREXAMPLE,
        EvidenceType.COUNTEREXAMPLE,
        MemoryTier.NEGATIVE,
        ClaimStatus.REJECTED,
        1.0,
        1.0,
        dependencies);
  }

  static MessageEnvelope message(
      String id,
      String statement,
      String conclusion,
      String route,
      String author,
      MessageType messageType,
      EvidenceType evidenceType,
      MemoryTier tier,
      ClaimStatus status,
      double confidence,
      double normalizationConfidence,
      List<String> dependencies) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        conclusion,
        "",
        null,
        dependencies,
        List.of(),
        evidenceType,
        tier,
        id,
        messageType,
        normalizationConfidence,
        statement,
        PROBLEM_HASH,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        author,
        RouteRole.PROVER,
        route,
        statement,
        List.of(),
        2,
        List.of(),
        confidence,
        status);
  }

  static ClaimCard claim(
      String id,
      List<String> dependencies,
      ClaimStatus status,
      String attemptId,
      Double confidence) {
    return new ClaimCard(
        List.of(),
        id,
        id,
        "",
        "unknown",
        dependencies,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.5,
        "author",
        attemptId,
        null,
        id,
        status,
        List.of(),
        confidence);
  }

  static ClaimCard claimWithStep(
      String id,
      String attemptId,
      ClaimStatus status,
      double confidence) {
    ProofStep step =
        new ProofStep(
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0.9,
            List.of(),
            List.of(),
            true,
            "audited justification",
            "local lemma step",
            id + "-step",
            "derivation");
    return new ClaimCard(
        List.of(),
        id,
        id,
        "",
        "unknown",
        List.of(),
        List.of(),
        List.of(),
        List.of(step),
        List.of(),
        0.9,
        "author",
        attemptId,
        "accepted-delta",
        id,
        status,
        List.of(),
        confidence);
  }

  static VerificationReport report(
      String targetId,
      String targetType,
      VerificationVerdict verdict,
      List<VerificationIssue> issues) {
    return new VerificationReport(
        "independent-verifier",
        List.of(),
        "checked",
        0.95,
        FailureLevel.NONE,
        null,
        issues,
        true,
        null,
        null,
        VerificationStage.LEMMA,
        List.of(),
        targetId,
        targetType,
        List.of(),
        List.of(),
        null,
        verdict);
  }
}
