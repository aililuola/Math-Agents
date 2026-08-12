package io.github.aililuola.mathproofmesh.verification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Legacy Claim cards are visible only in the explicitly selected legacy topology. */
public final class LegacyClaimQuarantine {
  private LegacyClaimQuarantine() {}

  public static List<ObjectNode> admissible(
      String topologyMode,
      List<ObjectNode> typedFactPackets,
      List<ObjectNode> legacyClaimPackets) {
    List<ObjectNode> selected =
        "legacy_sparse".equals(topologyMode)
            ? nullSafe(legacyClaimPackets)
            : nullSafe(typedFactPackets);
    return selected.stream().map(ObjectNode::deepCopy).toList();
  }

  private static List<ObjectNode> nullSafe(List<ObjectNode> values) {
    return values == null ? List.of() : values;
  }
}
