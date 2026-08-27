package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.Locale;

/** Classifies mathematical progress without treating necessary conditions as proof. */
public final class ProofRoleClassifier {
  public ProofControlModels.ProofRole classify(
      String text,
      ProofControlModels.GoalLink link,
      boolean counterexample,
      boolean boundedEvidence,
      boolean targetsCore) {
    if (counterexample) {
      return ProofControlModels.ProofRole.COUNTEREXAMPLE;
    }
    if (link != null) {
      return switch (link.relation()) {
        case EQUIVALENT -> ProofControlModels.ProofRole.EQUIVALENT_REDUCTION;
        case NECESSARY_ONLY -> ProofControlModels.ProofRole.NECESSARY_CONDITION;
        case HEURISTIC_ONLY -> ProofControlModels.ProofRole.SEARCH_HEURISTIC;
        case SUFFICIENT ->
            targetsCore
                ? ProofControlModels.ProofRole.CORE_BRIDGE
                : ProofControlModels.ProofRole.SUFFICIENT_CONDITION;
        case UNRELATED, UNKNOWN -> boundedEvidence
            ? ProofControlModels.ProofRole.SEARCH_HEURISTIC
            : fromText(text);
      };
    }
    return boundedEvidence ? ProofControlModels.ProofRole.SEARCH_HEURISTIC : fromText(text);
  }

  private static ProofControlModels.ProofRole fromText(String value) {
    String text =
        ProofIdentity.normalizeText(value).toLowerCase(Locale.ROOT);
    return text.contains("upper bound")
            || text.contains("lower bound")
            || text.contains("上界")
            || text.contains("下界")
        ? ProofControlModels.ProofRole.AUXILIARY_BOUND
        : ProofControlModels.ProofRole.TECHNICAL_LEMMA;
  }
}
