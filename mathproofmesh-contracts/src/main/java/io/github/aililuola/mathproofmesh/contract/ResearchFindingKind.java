package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Non-authoritative kinds for material public findings produced during research. */
public enum ResearchFindingKind {
  CANDIDATE_LEMMA,
  COUNTEREXAMPLE_CANDIDATE,
  EXACT_EXAMPLE,
  DISCARDED_HYPOTHESIS,
  SHARP_OBSTRUCTION,
  REPRESENTATION_INSIGHT,
  CONSTRUCTION_CANDIDATE,
  NEXT_MICRO_OBLIGATION;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static ResearchFindingKind fromValue(String value) {
    String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    try {
      return valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      throw new ContractValidationException("unknown ResearchFindingKind value: " + value);
    }
  }
}
