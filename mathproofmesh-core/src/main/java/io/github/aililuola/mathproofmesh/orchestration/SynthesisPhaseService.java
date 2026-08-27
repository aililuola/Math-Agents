package io.github.aililuola.mathproofmesh.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds blind synthesis context from verified dependency closure and selected negatives. */
public final class SynthesisPhaseService {
  public SynthesisPacket synthesize(
      List<VerifiedClaim> claims,
      Set<String> selectedClaimIds,
      List<NegativeEvidence> negatives,
      int negativeCharacterBudget) {
    if (negativeCharacterBudget < 0) {
      throw new IllegalArgumentException("negativeCharacterBudget must be nonnegative");
    }
    Map<String, VerifiedClaim> byId = new LinkedHashMap<>();
    for (VerifiedClaim claim : claims == null ? List.<VerifiedClaim>of() : claims) {
      byId.put(claim.claimId(), claim);
    }
    LinkedHashSet<String> closure = new LinkedHashSet<>();
    for (String selected : selectedClaimIds == null ? Set.<String>of() : selectedClaimIds) {
      close(selected, byId, closure, new LinkedHashSet<>());
    }
    List<String> factPackets =
        closure.stream()
            .map(byId::get)
            .filter(java.util.Objects::nonNull)
            .map(claim -> claim.claimId() + ": " + claim.statement())
            .toList();

    ArrayList<String> negativePackets = new ArrayList<>();
    int used = 0;
    int mandatoryOmitted = 0;
    List<NegativeEvidence> values = negatives == null ? List.of() : List.copyOf(negatives);
    for (NegativeEvidence negative : values) {
      String packet = negative.kind() + ": " + negative.statement();
      if (used + packet.length() <= negativeCharacterBudget) {
        negativePackets.add(packet);
        used += packet.length();
      } else if (negative.mandatory()) {
        mandatoryOmitted++;
      }
    }
    BlindNegativeSelection selection =
        new BlindNegativeSelection(
            negativePackets,
            values.size(),
            values.size() - negativePackets.size(),
            mandatoryOmitted,
            mandatoryOmitted == 0,
            negativePackets.size() < values.size(),
            used,
            negativeCharacterBudget);
    return new SynthesisPacket(factPackets, selection, mandatoryOmitted == 0);
  }

  private static void close(
      String claimId,
      Map<String, VerifiedClaim> byId,
      Set<String> closure,
      Set<String> visiting) {
    VerifiedClaim claim = byId.get(claimId);
    if (claim == null
        || !claim.verified()
        || !claim.independentlyReviewed()
        || closure.contains(claimId)) {
      throw new IllegalStateException("synthesis requires a verified dependency closure");
    }
    if (!visiting.add(claimId)) {
      throw new IllegalStateException("claim dependency cycle");
    }
    for (String dependency : claim.dependencies()) {
      close(dependency, byId, closure, visiting);
    }
    visiting.remove(claimId);
    closure.add(claimId);
  }

  public record VerifiedClaim(
      String claimId,
      String statement,
      List<String> dependencies,
      String authorAgentId,
      boolean verified,
      boolean independentlyReviewed) {
    public VerifiedClaim {
      claimId = required(claimId, "claimId");
      statement = required(statement, "statement");
      dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
      authorAgentId = authorAgentId == null ? "" : authorAgentId.strip();
    }

    @Override
    public List<String> dependencies() {
      return List.copyOf(dependencies);
    }
  }

  public record NegativeEvidence(String kind, String statement, boolean mandatory) {
    public NegativeEvidence {
      kind = required(kind, "kind");
      statement = required(statement, "statement");
    }
  }

  public record SynthesisPacket(
      List<String> verifiedFactPackets,
      BlindNegativeSelection negativeSelection,
      boolean complete) {
    public SynthesisPacket {
      verifiedFactPackets = List.copyOf(verifiedFactPackets);
      java.util.Objects.requireNonNull(negativeSelection, "negativeSelection");
    }

    @Override
    public List<String> verifiedFactPackets() {
      return List.copyOf(verifiedFactPackets);
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
