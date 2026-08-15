package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.MessageType;
import java.util.Set;

public final class BrokerControlBoundaryPolicy {
  private static final Set<MessageType> CONTROL_TYPES = Set.of(
      MessageType.FAILURE_RECORD,
      MessageType.REPAIR_REQUEST,
      MessageType.BRIDGE_LEMMA_REQUEST,
      MessageType.STRATEGY_REWRITE_REQUEST);

  public Decision audit(MessageType type, String exactMathematicalPayload) {
    if (type != null && CONTROL_TYPES.contains(type)) {
      return new Decision(false,
          type == MessageType.FAILURE_RECORD
              ? "GENERIC_FAILURE_RECORD"
              : "NON_MATHEMATICAL_CONTROL_MESSAGE");
    }
    if (exactMathematicalPayload == null || exactMathematicalPayload.isBlank()) {
      return new Decision(false, "MISSING_EXACT_MATHEMATICAL_PAYLOAD");
    }
    return new Decision(true, "ALLOW");
  }

  public record Decision(boolean allowed, String code) {}
}
