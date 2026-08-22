package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/** Immutable pricing bound to the actual provider/model selected for a physical call. */
public record PricingSnapshot(
    String provider,
    String model,
    BigDecimal inputPerMillion,
    BigDecimal outputPerMillion,
    BillingMode billingMode,
    String configHash,
    String pricingHash) {

  public enum BillingMode {
    BILLED,
    BILLING_EXEMPT,
    UNKNOWN
  }

  public PricingSnapshot {
    provider = required(provider, "provider");
    model = required(model, "model");
    inputPerMillion = normalize(inputPerMillion, "inputPerMillion");
    outputPerMillion = normalize(outputPerMillion, "outputPerMillion");
    billingMode = Objects.requireNonNull(billingMode, "billingMode");
    configHash = required(configHash, "configHash");
    if (billingMode == BillingMode.BILLED
        && (inputPerMillion.signum() == 0 || outputPerMillion.signum() == 0)) {
      throw new IllegalArgumentException("billed providers require nonzero input and output pricing");
    }
    if (billingMode == BillingMode.BILLING_EXEMPT
        && (inputPerMillion.signum() != 0 || outputPerMillion.signum() != 0)) {
      throw new IllegalArgumentException("billing-exempt pricing must be zero");
    }
    String expected =
        CanonicalJson.stableHash(
            Map.of(
                "provider", provider,
                "model", model,
                "input_per_million", inputPerMillion,
                "output_per_million", outputPerMillion,
                "billing_mode", billingMode.name(),
                "config_hash", configHash));
    pricingHash = pricingHash == null || pricingHash.isBlank() ? expected : pricingHash.strip();
    if (!sameHash(expected, pricingHash)) {
      throw new IllegalArgumentException("pricing snapshot hash mismatch");
    }
  }

  public boolean usableWithCostCap() {
    return billingMode == BillingMode.BILLED || billingMode == BillingMode.BILLING_EXEMPT;
  }

  public static PricingSnapshot legacyUnknown() {
    return new PricingSnapshot(
        "legacy",
        "legacy",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BillingMode.UNKNOWN,
        "legacy-unclassified",
        null);
  }

  private static BigDecimal normalize(BigDecimal value, String field) {
    BigDecimal result = Objects.requireNonNull(value, field);
    if (result.signum() < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
