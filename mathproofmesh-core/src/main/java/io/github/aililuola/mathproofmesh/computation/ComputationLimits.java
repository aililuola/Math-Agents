package io.github.aililuola.mathproofmesh.computation;

/** Immutable computation-policy limits owned by the framework-free core. */
public record ComputationLimits(
    boolean enabled,
    boolean typedToolsEnabled,
    boolean sandboxedPythonEnabled,
    boolean targetedFalsificationFastPath,
    boolean boundedTypedProbeFastPath,
    int boundedTypedProbeMaxCases,
    int softExperimentsPerPath,
    int hardExperimentsPerPath,
    double maxTotalCpuSeconds,
    int maxCasesPerExperiment,
    int maxOutputChars,
    int broadSearchAfterStalledRounds,
    boolean broadSearchRequiresMetaReview,
    boolean cacheResults) {

  public ComputationLimits {
    if (boundedTypedProbeMaxCases < 1
        || softExperimentsPerPath < 0
        || hardExperimentsPerPath < 1
        || softExperimentsPerPath > hardExperimentsPerPath
        || maxTotalCpuSeconds <= 0
        || maxCasesPerExperiment < 1
        || maxOutputChars < 256
        || broadSearchAfterStalledRounds < 0) {
      throw new IllegalArgumentException("invalid computation limits");
    }
  }

  public static ComputationLimits defaultsEnabled() {
    return new ComputationLimits(
        true,
        true,
        false,
        true,
        true,
        25_000,
        2,
        6,
        120.0,
        1_000_000,
        20_000,
        1,
        true,
        true);
  }
}
