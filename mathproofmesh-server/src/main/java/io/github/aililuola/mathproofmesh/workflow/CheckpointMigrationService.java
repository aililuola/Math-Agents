package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Typed, idempotent migration for v0.7 through v0.8.2 checkpoint sidecars. */
public final class CheckpointMigrationService {
  public MigratedCheckpoint migrate(LegacyCheckpoint source) {
    java.util.Objects.requireNonNull(source, "source");
    ArrayList<String> quarantined = new ArrayList<>(source.quarantinedClaimIds());
    for (ClaimLink link : source.claimLinks()) {
      if (link.claimId().equals(link.dependencyClaimId())
          && !quarantined.contains(link.claimId())) {
        quarantined.add(link.claimId());
      }
    }
    List<String> routes =
        source.routeIds().isEmpty() ? List.of("route-rebuilt-0") : source.routeIds();
    String checkpointId =
        "checkpoint_"
            + CanonicalJson.stableHash(
                    List.of(
                        source.runId(),
                        source.problemHash(),
                        source.strategyIds(),
                        routes,
                        source.proofControlState(),
                        source.inspirationActionKeys(),
                        quarantined))
                .substring(0, 16);
    return new MigratedCheckpoint(
        "0.8.2",
        source.runId(),
        source.problemHash(),
        checkpointId,
        routes,
        source.strategyIds(),
        source.proofControlState(),
        source.inspirationActionKeys().stream().distinct().toList(),
        quarantined,
        List.of());
  }

  public record LegacyCheckpoint(
      String version,
      String runId,
      String problemHash,
      List<String> routeIds,
      List<String> strategyIds,
      List<String> proofControlState,
      List<String> inspirationActionKeys,
      List<ClaimLink> claimLinks,
      List<String> quarantinedClaimIds,
      List<String> modelAuthoredHashes) {
    public LegacyCheckpoint {
      version = required(version, "version");
      runId = required(runId, "runId");
      problemHash = required(problemHash, "problemHash");
      routeIds = copy(routeIds);
      strategyIds = copy(strategyIds);
      proofControlState = copy(proofControlState);
      inspirationActionKeys = copy(inspirationActionKeys);
      claimLinks = claimLinks == null ? List.of() : List.copyOf(claimLinks);
      quarantinedClaimIds = copy(quarantinedClaimIds);
      modelAuthoredHashes = copy(modelAuthoredHashes);
    }

    @Override
    public List<String> routeIds() {
      return List.copyOf(routeIds);
    }

    @Override
    public List<String> strategyIds() {
      return List.copyOf(strategyIds);
    }

    @Override
    public List<String> proofControlState() {
      return List.copyOf(proofControlState);
    }

    @Override
    public List<String> inspirationActionKeys() {
      return List.copyOf(inspirationActionKeys);
    }

    @Override
    public List<ClaimLink> claimLinks() {
      return List.copyOf(claimLinks);
    }

    @Override
    public List<String> quarantinedClaimIds() {
      return List.copyOf(quarantinedClaimIds);
    }

    @Override
    public List<String> modelAuthoredHashes() {
      return List.copyOf(modelAuthoredHashes);
    }
  }

  public record ClaimLink(String claimId, String dependencyClaimId) {
    public ClaimLink {
      claimId = required(claimId, "claimId");
      dependencyClaimId = required(dependencyClaimId, "dependencyClaimId");
    }
  }

  public record MigratedCheckpoint(
      String version,
      String runId,
      String problemHash,
      String checkpointId,
      List<String> routeIds,
      List<String> strategyIds,
      List<String> proofControlState,
      List<String> inspirationActionKeys,
      List<String> quarantinedClaimIds,
      List<String> acceptedModelAuthoredHashes) {
    public MigratedCheckpoint {
      routeIds = unique(routeIds);
      strategyIds = unique(strategyIds);
      proofControlState = copy(proofControlState);
      inspirationActionKeys = unique(inspirationActionKeys);
      quarantinedClaimIds = unique(quarantinedClaimIds);
      acceptedModelAuthoredHashes = copy(acceptedModelAuthoredHashes);
    }

    @Override
    public List<String> routeIds() {
      return List.copyOf(routeIds);
    }

    @Override
    public List<String> strategyIds() {
      return List.copyOf(strategyIds);
    }

    @Override
    public List<String> proofControlState() {
      return List.copyOf(proofControlState);
    }

    @Override
    public List<String> inspirationActionKeys() {
      return List.copyOf(inspirationActionKeys);
    }

    @Override
    public List<String> quarantinedClaimIds() {
      return List.copyOf(quarantinedClaimIds);
    }

    @Override
    public List<String> acceptedModelAuthoredHashes() {
      return List.copyOf(acceptedModelAuthoredHashes);
    }
  }

  private static List<String> unique(List<String> values) {
    return List.copyOf(new LinkedHashSet<>(copy(values)));
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
