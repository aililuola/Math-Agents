package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ResearchFindingDispositionAction {
  KEEP_ACTIVE,
  DEFER,
  PROMOTE_TO_PROPOSED_LEMMA,
  PROMOTE_TO_COUNTEREXAMPLE_CANDIDATE,
  REJECT_WITH_REASON,
  SUPERSEDE_WITH;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static ResearchFindingDispositionAction fromValue(String value) {
    String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    try {
      return valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      throw new ContractValidationException(
          "unknown ResearchFindingDispositionAction value: " + value);
    }
  }
}
