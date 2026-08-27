package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InspirationOutcome;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.NegativeAnalogyRecord;
import io.github.aililuola.mathproofmesh.contract.VerifiedExperienceRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Distills only Fact-gated results; rejected transfers become negative records. */
public final class VerifiedExperienceDistiller {
  public Optional<VerifiedExperienceRecord> distillPositive(
      InspirationProposal proposal,
      InspirationOutcome outcome,
      boolean factGated,
      boolean citedByFinalProof,
      String problemSkeleton,
      String proofSummary) {
    if (!factGated
        || outcome.verifiedFactGain() <= 0
        || !outcome.materialized()
        || outcome.refuted()) {
      return Optional.empty();
    }
    String id =
        "experience_"
            + CanonicalJson.stableHash(
                    List.of(proposal.proposalId(), proposal.noveltySignature().normalizedHash()))
                .substring(0, 16);
    return Optional.of(
        new VerifiedExperienceRecord(
            citedByFinalProof,
            List.of(),
            constructionSummary(proposal),
            mechanismChain(proposal),
            proposal.noveltySignature().mechanismTags(),
            List.of(),
            transferConditions(proposal),
            Map.of(),
            proposal.noveltySignature().coreObjects(),
            List.of(),
            outcome.obligationKinds().stream().map(item -> item.value()).toList(),
            Map.of(),
            proposal.noveltySignature().keyTransformations(),
            outcome.problemHash(),
            required(problemSkeleton, "problemSkeleton"),
            proposal.statement(),
            proposal.noveltySignature().proofPrinciples(),
            proofSummary == null ? "" : proofSummary,
            id,
            proposal.noveltySignature().representationTags(),
            List.of(),
            proposal.proposalId(),
            List.of("verify target-domain preconditions before transfer"),
            outcome.obligationsClosed(),
            true));
  }

  public NegativeAnalogyRecord distillNegative(
      InspirationProposal proposal,
      String problemHash,
      int roundIndex,
      String failureReason,
      List<String> distinguishingConditions,
      String sourceRecordId) {
    String id =
        "negative_"
            + CanonicalJson.stableHash(
                    List.of(problemHash, proposal.proposalId(), failureReason))
                .substring(0, 16);
    return new NegativeAnalogyRecord(
        distinguishingConditions,
        required(failureReason, "failureReason"),
        proposal.mechanism(),
        true,
        required(problemHash, "problemHash"),
        proposal.proposalId(),
        id,
        roundIndex,
        sourceRecordId);
  }

  private static List<String> mechanismChain(InspirationProposal proposal) {
    List<String> chain = new java.util.ArrayList<>();
    chain.addAll(proposal.noveltySignature().representationTags());
    chain.addAll(proposal.noveltySignature().keyTransformations());
    chain.addAll(proposal.noveltySignature().proofPrinciples());
    return List.copyOf(chain);
  }

  private static List<String> transferConditions(InspirationProposal proposal) {
    if (proposal.analogy() != null) {
      return proposal.analogy().nonTransferableConditions();
    }
    return List.of("independent review required in the target problem");
  }

  private static String constructionSummary(InspirationProposal proposal) {
    if (proposal.construction() != null) {
      return proposal.construction().definition();
    }
    return "";
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
