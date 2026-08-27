package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import java.util.List;
import java.util.Map;

public record AdmissionResult(
    boolean accepted,
    AdmissionRejection rejection,
    String reason,
    List<String> selectedTargets,
    Map<String, String> rejectedTargets,
    MessagePriority priority) {

  public AdmissionResult {
    reason = reason == null ? "" : reason;
    selectedTargets = selectedTargets == null ? List.of() : List.copyOf(selectedTargets);
    rejectedTargets = rejectedTargets == null ? Map.of() : Map.copyOf(rejectedTargets);
  }

  public static AdmissionResult reject(AdmissionRejection rejection, String reason) {
    return new AdmissionResult(false, rejection, reason, List.of(), Map.of(), null);
  }

  public static AdmissionResult accept(
      List<String> selectedTargets,
      Map<String, String> rejectedTargets,
      MessagePriority priority) {
    return new AdmissionResult(true, null, "", selectedTargets, rejectedTargets, priority);
  }
}
