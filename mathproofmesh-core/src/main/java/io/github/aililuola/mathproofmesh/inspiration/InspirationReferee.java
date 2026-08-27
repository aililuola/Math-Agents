package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationReview;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent structural referee. Novelty is explicitly not correctness. */
public final class InspirationReferee {
  private final InspirationPolicy policy;

  public InspirationReferee(InspirationPolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
  }

  public InspirationReview review(
      InspirationProposal proposal,
      String reviewerAgentId,
      Set<String> openObligationIds,
      List<NoveltySignature> existingSignatures,
      List<String> immediateCounterexamples,
      List<String> hiddenAssumptions) {
    String reviewer = required(reviewerAgentId, "reviewerAgentId");
    if (reviewer.equals(proposal.sourceAgentId())) {
      throw new IllegalArgumentException("an inspiration author cannot referee its own proposal");
    }
    NoveltyAssessment assessment =
        new NoveltyGate(policy.novelty())
            .assess(proposal.noveltySignature(), existingSignatures);
    Set<String> targets = new LinkedHashSet<>(proposal.noveltySignature().targetedObligationIds());
    targets.addAll(proposal.generatedObligations());
    boolean relevant =
        targets.stream().anyMatch((openObligationIds == null ? Set.<String>of() : openObligationIds)::contains);
    List<String> counterexamples =
        immediateCounterexamples == null ? List.of() : List.copyOf(immediateCounterexamples);
    boolean coherent = !proposal.statement().isBlank() && counterexamples.isEmpty();
    boolean distinct =
        !assessment.duplicate()
            && proposal.noveltyScore() >= policy.novelty().threshold()
            && assessment.noveltyScore() >= policy.novelty().threshold();
    String recommendation;
    if (!coherent || !relevant || !distinct) {
      recommendation = "reject";
    } else if (proposal.estimatedCost() == 0) {
      recommendation = "store_insight";
    } else if (!proposal.targetRouteIds().isEmpty()) {
      recommendation = "attach_to_existing_route";
    } else {
      recommendation = "create_new_route";
    }
    double confidence =
        0.25d
            + (distinct ? 0.25d : 0.0d)
            + (relevant ? 0.25d : 0.0d)
            + (coherent ? 0.25d : 0.0d);
    return new InspirationReview(
        confidence,
        "",
        hiddenAssumptions == null ? List.of() : hiddenAssumptions,
        counterexamples,
        coherent,
        proposal.proposalId(),
        recommendation,
        relevant,
        null,
        "completed",
        reviewer,
        distinct);
  }

  public Map<String, Object> negativeBlindPacket(
      InspirationProposal proposal, String failureReason) {
    Map<String, Object> packet = new LinkedHashMap<>();
    packet.put("proposal_id", proposal.proposalId());
    packet.put("mechanism", proposal.mechanism().value());
    packet.put("statement", proposal.statement());
    packet.put("signature_hash", proposal.noveltySignature().normalizedHash());
    packet.put("failure_reason", required(failureReason, "failureReason"));
    packet.put("negative", true);
    return Map.copyOf(packet);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
