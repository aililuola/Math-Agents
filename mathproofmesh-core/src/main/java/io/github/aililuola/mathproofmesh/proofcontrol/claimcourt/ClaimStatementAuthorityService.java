package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecision;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import io.github.aililuola.mathproofmesh.memory.NegativeMatchStrength;
import java.util.ArrayList;
import java.util.List;

/** Resolves model candidates against existing trusted evidence without granting model authority. */
public final class ClaimStatementAuthorityService {
  public record Result(
      ClaimStatementAssessment assessment,
      List<String> evidenceIds,
      String detail) {
    public Result {
      assessment = java.util.Objects.requireNonNull(assessment, "assessment");
      evidenceIds = ClaimCourtValues.copy(evidenceIds);
      detail = ClaimCourtValues.required(detail, "detail");
    }

    @Override
    public List<String> evidenceIds() {
      return List.copyOf(evidenceIds);
    }
  }

  public Result assess(
      FrozenClaimSnapshot frozen,
      ClaimStatementFalsificationDecision modelDecision,
      NegativeKnowledgeRegistry negativeRegistry,
      int currentRound,
      List<ClaimRefutationEvidence> trustedEvidence) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    java.util.Objects.requireNonNull(modelDecision, "modelDecision");
    java.util.Objects.requireNonNull(negativeRegistry, "negativeRegistry");
    if (!frozen.claimId().equals(modelDecision.claimId())) {
      throw new IllegalArgumentException("statement falsification targets another claim");
    }

    List<String> exactEvidence = new ArrayList<>();
    for (ClaimRefutationEvidence evidence : ClaimCourtValues.copy(trustedEvidence)) {
      if (evidence.exactFor(frozen)) {
        exactEvidence.add(evidence.evidenceId());
      }
    }
    if (!exactEvidence.isEmpty()) {
      return new Result(
          ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE,
          exactEvidence,
          "VERIFIED_EXACT_REFUTATION");
    }

    NegativeKnowledgeCandidate candidate =
        new NegativeKnowledgeCandidate(
            frozen.problemHash(),
            NegativeKnowledgeTargetType.CLAIM,
            frozen.statement(),
            "",
            frozen.assumptions(),
            frozen.quantifiers(),
            frozen.variableBindings(),
            frozen.scopeLimitations(),
            NegativeKnowledgeSurface.RESTORE_REVALIDATION,
            NegativeCandidateIntent.FALSIFICATION_ONLY);
    NegativeKnowledgeDecision negativeDecision =
        negativeRegistry.decide(candidate, currentRound);
    if (negativeDecision.matchStrength() == NegativeMatchStrength.EXACT
        || negativeDecision.matchStrength() == NegativeMatchStrength.TRUSTED_ALIAS) {
      List<String> permanentIds =
          negativeRegistry.records().stream()
              .filter(record -> record.permanent())
              .filter(record -> negativeDecision.matchedNegativeIds().contains(record.negativeId()))
              .map(record -> record.negativeId())
              .toList();
      if (!permanentIds.isEmpty()) {
        return new Result(
            ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE,
            permanentIds,
            "PERMANENT_NEGATIVE_EXACT_OR_TRUSTED_ALIAS");
      }
    }
    if (negativeDecision.matchStrength() == NegativeMatchStrength.POSSIBLE_EQUIVALENT
        || modelDecision.disposition() == StatementFalsificationDisposition.INCONCLUSIVE) {
      return new Result(
          ClaimStatementAssessment.INCONCLUSIVE,
          List.of(),
          "UNVERIFIED_OR_POSSIBLE_EQUIVALENT_REFUTATION");
    }
    return new Result(
        ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION,
        List.of(),
        modelDecision.disposition() == StatementFalsificationDisposition.COUNTEREXAMPLE_CANDIDATE
            ? "MODEL_COUNTEREXAMPLE_CANDIDATE_HAS_NO_AUTHORITY"
            : "NO_COUNTEREXAMPLE_FOUND_DOES_NOT_GRANT_AUTHORITY");
  }
}
