package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import java.util.LinkedHashSet;
import java.util.List;

/** Enforces author independence and cross-stage separation before provider work begins. */
public final class ClaimCourtRolePolicy {
  public record Assignment(
      String authorAgentId,
      String falsifierAgentId,
      String auditorAgentId,
      String repairerAgentId,
      String blindAdjudicatorAgentId) {
    public Assignment {
      authorAgentId = ClaimCourtValues.required(authorAgentId, "authorAgentId");
      falsifierAgentId = ClaimCourtValues.required(falsifierAgentId, "falsifierAgentId");
      auditorAgentId = ClaimCourtValues.required(auditorAgentId, "auditorAgentId");
      repairerAgentId = ClaimCourtValues.required(repairerAgentId, "repairerAgentId");
      blindAdjudicatorAgentId =
          ClaimCourtValues.required(blindAdjudicatorAgentId, "blindAdjudicatorAgentId");
    }
  }

  public boolean independent(Assignment assignment) {
    java.util.Objects.requireNonNull(assignment, "assignment");
    return new LinkedHashSet<>(
                List.of(
                    assignment.authorAgentId(),
                    assignment.falsifierAgentId(),
                    assignment.auditorAgentId(),
                    assignment.repairerAgentId(),
                    assignment.blindAdjudicatorAgentId()))
            .size()
        == 5;
  }

  public void requireIndependent(Assignment assignment) {
    if (!independent(assignment)) {
      throw new IllegalArgumentException("CLAIM_COURT_INDEPENDENCE_UNAVAILABLE");
    }
  }
}
