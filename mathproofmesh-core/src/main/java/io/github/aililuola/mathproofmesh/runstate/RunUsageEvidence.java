package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;
import java.util.Objects;

public record RunUsageEvidence(
    RunUsageEvidenceSource source,
    RunUsageSnapshot aggregate,
    List<ProviderCallUsageEvidence> providerCalls,
    String evidenceRef) {
  public RunUsageEvidence {
    source = Objects.requireNonNull(source, "source");
    aggregate = aggregate == null ? RunUsageSnapshot.empty() : aggregate;
    providerCalls = providerCalls == null ? List.of() : List.copyOf(providerCalls);
    evidenceRef = RunStateHashes.optional(evidenceRef);
  }

  @Override
  public List<ProviderCallUsageEvidence> providerCalls() {
    return List.copyOf(providerCalls);
  }

  public static RunUsageEvidence aggregate(
      RunUsageEvidenceSource source, RunUsageSnapshot snapshot, String evidenceRef) {
    return new RunUsageEvidence(source, snapshot, List.of(), evidenceRef);
  }

  public static RunUsageEvidence providerCalls(
      List<ProviderCallUsageEvidence> calls, String evidenceRef) {
    return new RunUsageEvidence(
        RunUsageEvidenceSource.DURABLE_PROVIDER_REQUESTS,
        RunUsageSnapshot.empty(),
        calls,
        evidenceRef);
  }
}
