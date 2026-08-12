package io.github.aililuola.mathproofmesh.verification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Result plus diagnostics for a bounded typed-Fact context selection. */
public record FactContextSelection(
    List<ObjectNode> packets,
    List<String> selectedMessageIds,
    List<String> missingRequiredRefs,
    int usedChars,
    int maxChars,
    boolean truncated) {

  public FactContextSelection {
    packets = packets == null ? List.of() : packets.stream().map(ObjectNode::deepCopy).toList();
    selectedMessageIds =
        selectedMessageIds == null ? List.of() : List.copyOf(selectedMessageIds);
    missingRequiredRefs =
        missingRequiredRefs == null ? List.of() : List.copyOf(missingRequiredRefs);
    if (usedChars < 0 || maxChars < 0 || usedChars > maxChars) {
      throw new IllegalArgumentException("context character accounting is invalid");
    }
  }

  @Override
  public List<ObjectNode> packets() {
    return packets.stream().map(ObjectNode::deepCopy).toList();
  }

  public boolean requiredContextComplete() {
    return missingRequiredRefs.isEmpty();
  }
}
