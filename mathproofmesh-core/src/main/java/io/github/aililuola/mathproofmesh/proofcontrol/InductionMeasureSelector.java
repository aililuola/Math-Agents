package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Selects executable, well-founded induction measures bound to the trigger. */
public final class InductionMeasureSelector {
  public record Proposal(
      String id,
      String routeId,
      List<String> targetObligationIds,
      String measureName,
      String wellFoundedDomain,
      List<String> baseCases,
      String stepRelation,
      String strictDecreaseArgument,
      String naturalIndexInsufficiency,
      List<String> triggerFeatures,
      String sourceAgentId,
      String status,
      String reviewerAgentId,
      List<String> reviewEvidenceIds,
      String activationActionId) {
    public Proposal {
      targetObligationIds = List.copyOf(targetObligationIds);
      baseCases = List.copyOf(baseCases);
      triggerFeatures = List.copyOf(triggerFeatures);
      reviewEvidenceIds = List.copyOf(reviewEvidenceIds);
    }
  }

  private final Map<String, Proposal> proposals = new LinkedHashMap<>();
  private final Map<String, String> activationByProposal = new LinkedHashMap<>();

  public boolean detectTrigger(String text) {
    String normalized =
        ProofIdentity.normalizeText(text).toLowerCase(Locale.ROOT);
    return normalized.contains("ordinary induction")
            && (normalized.contains("occurrence") || normalized.contains("repeated"))
        || normalized.contains("first occurrence")
        || normalized.contains("strictly smaller");
  }

  public Proposal propose(
      String routeId,
      List<String> targetObligationIds,
      String triggerText,
      String sourceAgentId) {
    if (targetObligationIds == null || targetObligationIds.isEmpty()) {
      throw new IllegalArgumentException(
          "induction measure must bind a triggering obligation");
    }
    String normalized =
        ProofIdentity.normalizeText(triggerText).toLowerCase(Locale.ROOT);
    boolean occurrence =
        normalized.contains("occurrence")
            || normalized.contains("feature appears repeatedly");
    String measure = occurrence ? "occurrence_count" : "structural_complexity";
    String domain = "nonnegative_integers";
    List<String> base = List.of(measure + " = 0");
    String relation = measure + "(next) < " + measure + "(current)";
    String decrease =
        occurrence
            ? "removing the selected occurrence strictly lowers occurrence_count"
            : "the recursive subobject has strictly smaller structural_complexity";
    String id =
        "induction_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "route", routeId,
                        "targets", targetObligationIds.stream().sorted().toList(),
                        "measure", measure,
                        "trigger", normalized))
                .substring(0, 20);
    Proposal proposal =
        new Proposal(
            id,
            routeId,
            targetObligationIds.stream().distinct().sorted().toList(),
            measure,
            domain,
            base,
            relation,
            decrease,
            occurrence
                ? "plain index does not decrease at the first occurrence barrier"
                : "plain index is not aligned with structural descent",
            List.of(normalized),
            sourceAgentId,
            "candidate",
            null,
            List.of(),
            null);
    proposals.putIfAbsent(id, proposal);
    return proposals.get(id);
  }

  public boolean wellFounded(Proposal proposal) {
    String domain = proposal.wellFoundedDomain().toLowerCase(Locale.ROOT);
    String decrease = proposal.strictDecreaseArgument().toLowerCase(Locale.ROOT);
    return (domain.contains("nonnegative") || domain.contains("natural"))
        && !proposal.baseCases().isEmpty()
        && (decrease.contains("strict") || decrease.contains("lowers"))
        && proposal.stepRelation().contains("<");
  }

  public Proposal accept(
      String proposalId, String reviewerAgentId, List<String> evidenceIds) {
    Proposal proposal = require(proposalId);
    if (!wellFounded(proposal)) {
      throw new IllegalArgumentException("induction measure is not well founded");
    }
    if (reviewerAgentId == null
        || reviewerAgentId.isBlank()
        || reviewerAgentId.equals(proposal.sourceAgentId())) {
      throw new IllegalArgumentException(
          "induction activation requires an independent reviewer");
    }
    if (evidenceIds == null || evidenceIds.isEmpty()) {
      throw new IllegalArgumentException(
          "induction activation requires review evidence");
    }
    String action =
        activationByProposal.computeIfAbsent(
            proposalId,
            ignored ->
                "activate_induction_"
                    + CanonicalJson.stableHash(
                            Map.of(
                                "proposal", proposalId,
                                "reviewer", reviewerAgentId,
                                "evidence", evidenceIds.stream().sorted().toList()))
                        .substring(0, 20));
    Proposal accepted =
        new Proposal(
            proposal.id(),
            proposal.routeId(),
            proposal.targetObligationIds(),
            proposal.measureName(),
            proposal.wellFoundedDomain(),
            proposal.baseCases(),
            proposal.stepRelation(),
            proposal.strictDecreaseArgument(),
            proposal.naturalIndexInsufficiency(),
            proposal.triggerFeatures(),
            proposal.sourceAgentId(),
            "accepted",
            reviewerAgentId,
            evidenceIds.stream().distinct().sorted().toList(),
            action);
    proposals.put(proposalId, accepted);
    return accepted;
  }

  public String promptDirective(Proposal proposal) {
    if (!"accepted".equals(proposal.status())) {
      throw new IllegalArgumentException(
          "only an independently reviewed measure may enter a route prompt");
    }
    return "[INDUCTION_MEASURE:"
        + proposal.measureName()
        + "] targets="
        + String.join(",", proposal.targetObligationIds())
        + " base="
        + String.join(",", proposal.baseCases())
        + " decrease="
        + proposal.strictDecreaseArgument();
  }

  private Proposal require(String id) {
    Proposal proposal = proposals.get(id);
    if (proposal == null) {
      throw new IllegalArgumentException("unknown induction proposal: " + id);
    }
    return proposal;
  }
}
