package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Server-derived mathematical authority carried by a verification receipt. */
public enum ComputationVerifiedAuthority {
  AUDIT_ONLY,
  BOUNDED_OBSERVATION,
  EXACT_COUNTEREXAMPLE,
  FINITE_DOMAIN_CERTIFICATE,
  FORMAL_CERTIFICATE;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static ComputationVerifiedAuthority fromValue(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (RuntimeException exception) {
      throw new ContractValidationException(
          "unknown ComputationVerifiedAuthority value: " + value, exception);
    }
  }
}
