package io.github.aililuola.mathproofmesh.orchestration;

import java.util.List;

public record CertifiedGainSnapshot(int schemaVersion, List<CertifiedGainReceipt> receipts) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public CertifiedGainSnapshot {
    if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported certified-gain snapshot schema");
    }
    receipts = receipts == null ? List.of() : List.copyOf(receipts);
  }

  public static CertifiedGainSnapshot empty() {
    return new CertifiedGainSnapshot(CURRENT_SCHEMA_VERSION, List.of());
  }
}
