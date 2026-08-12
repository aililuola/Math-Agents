package io.github.aililuola.mathproofmesh.orchestration;

import java.util.ArrayList;
import java.util.List;

/**
 * The sole cross-route publication boundary. It accepts only independently reviewed,
 * validation-passing claims and strips agent identity and raw reasoning.
 */
public final class BrokerPhaseService {
  public List<BrokerPacket> publish(List<ReviewedClaim> claims, String sourceDeltaId) {
    String delta = required(sourceDeltaId, "sourceDeltaId");
    ArrayList<BrokerPacket> packets = new ArrayList<>();
    for (ReviewedClaim claim : claims == null ? List.<ReviewedClaim>of() : claims) {
      if (claim.accepted()
          && claim.validationPassed()
          && claim.globalShareAllowed()
          && delta.equals(claim.sourceDeltaId())) {
        packets.add(
            new BrokerPacket(
                claim.claimId(),
                claim.statement(),
                claim.dependencies(),
                claim.evidenceRefs(),
                delta,
                true));
      }
    }
    return List.copyOf(packets);
  }

  public record ReviewedClaim(
      String claimId,
      String statement,
      List<String> dependencies,
      List<String> evidenceRefs,
      String sourceDeltaId,
      String authorAgentId,
      String rawReasoning,
      boolean accepted,
      boolean validationPassed,
      boolean globalShareAllowed) {
    public ReviewedClaim {
      claimId = required(claimId, "claimId");
      statement = required(statement, "statement");
      dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
      sourceDeltaId = required(sourceDeltaId, "sourceDeltaId");
      authorAgentId = authorAgentId == null ? "" : authorAgentId.strip();
      rawReasoning = rawReasoning == null ? "" : rawReasoning;
    }

    @Override
    public List<String> dependencies() {
      return List.copyOf(dependencies);
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  public record BrokerPacket(
      String claimId,
      String statement,
      List<String> dependencies,
      List<String> evidenceRefs,
      String sourceDeltaId,
      boolean crossRouteBoundary) {
    public BrokerPacket {
      dependencies = List.copyOf(dependencies);
      evidenceRefs = List.copyOf(evidenceRefs);
    }

    @Override
    public List<String> dependencies() {
      return List.copyOf(dependencies);
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
