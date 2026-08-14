package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StrategyCriticalClaimPreflight {
  private final CriticalClaimKeyCompiler keyCompiler;
  private final List<StrategyPreflightEvidenceSource> evidenceSources;

  public StrategyCriticalClaimPreflight(
      CriticalClaimKeyCompiler keyCompiler,
      List<StrategyPreflightEvidenceSource> evidenceSources) {
    this.keyCompiler = java.util.Objects.requireNonNull(keyCompiler, "keyCompiler");
    this.evidenceSources = evidenceSources == null ? List.of() : List.copyOf(evidenceSources);
  }

  public StrategyPreflightReport evaluate(String problemHash, StrategyCard strategy) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    List<CriticalClaimPreflightResult> results = new ArrayList<>();
    Set<String> unresolvedRequired = new LinkedHashSet<>();
    boolean hardRejected = false;
    boolean requiresRegeneration = false;
    int requiredCount = 0;
    int supportedRequired = 0;
    for (CriticalClaim claim : strategy.criticalClaims()) {
      CriticalClaimSemanticKey key = keyCompiler.compile(problemHash, claim);
      CriticalClaimPreflightSpec spec =
          new CriticalClaimPreflightSpec(
              problemHash,
              claim,
              key,
              claim.preferredTool() == null ? "" : claim.preferredTool(),
              claim.evidenceRefs());
      List<CriticalClaimPreflightEvidence> evidence =
          evidenceSources.stream()
              .map(source -> safelyEvaluate(source, key, spec))
              .flatMap(Optional::stream)
              .toList();
      CriticalClaimPreflightStatus status = aggregate(evidence, spec);
      String necessity = claim.necessity();
      boolean required = "required".equals(necessity);
      if (required) {
        requiredCount++;
        if (status == CriticalClaimPreflightStatus.VERIFIED_SUPPORTED) {
          supportedRequired++;
        } else if (status != CriticalClaimPreflightStatus.VERIFIED_REFUTED
            && status != CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED
            && status != CriticalClaimPreflightStatus.ERROR) {
          unresolvedRequired.add(key.semanticKey());
        }
        hardRejected |=
            status == CriticalClaimPreflightStatus.VERIFIED_REFUTED
                || status == CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED
                || status == CriticalClaimPreflightStatus.ERROR;
      } else if (status == CriticalClaimPreflightStatus.VERIFIED_REFUTED
          || status == CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED) {
        requiresRegeneration = true;
      }
      results.add(
          new CriticalClaimPreflightResult(
              claim.claimId(),
              key,
              necessity,
              status,
              evidence,
              statusDetail(status, evidence)));
    }
    double coverage = requiredCount == 0 ? 0.0d : (double) supportedRequired / requiredCount;
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("strategy_id", strategy.strategyId());
    identity.put("problem_hash", problemHash);
    identity.put("claims", results);
    identity.put("hard_rejected", hardRejected);
    identity.put("requires_regeneration", requiresRegeneration);
    return new StrategyPreflightReport(
        strategy.strategyId(),
        problemHash,
        results,
        hardRejected,
        requiresRegeneration,
        coverage,
        unresolvedRequired,
        StrategySemanticNormalizer.hash(identity));
  }

  private static Optional<CriticalClaimPreflightEvidence> safelyEvaluate(
      StrategyPreflightEvidenceSource source,
      CriticalClaimSemanticKey key,
      CriticalClaimPreflightSpec spec) {
    try {
      return source.evaluate(key, spec);
    } catch (RuntimeException exception) {
      return Optional.of(
          new CriticalClaimPreflightEvidence(
              CriticalClaimPreflightStatus.ERROR,
              "preflight-source",
              List.of(),
              exception.getClass().getSimpleName()));
    }
  }

  private static CriticalClaimPreflightStatus aggregate(
      List<CriticalClaimPreflightEvidence> evidence, CriticalClaimPreflightSpec spec) {
    Set<CriticalClaimPreflightStatus> statuses =
        evidence.stream().map(CriticalClaimPreflightEvidence::status).collect(java.util.stream.Collectors.toSet());
    boolean support = statuses.contains(CriticalClaimPreflightStatus.VERIFIED_SUPPORTED);
    boolean refutation =
        statuses.contains(CriticalClaimPreflightStatus.VERIFIED_REFUTED)
            || statuses.contains(CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED);
    if (support && refutation || statuses.contains(CriticalClaimPreflightStatus.ERROR)) {
      return CriticalClaimPreflightStatus.ERROR;
    }
    if (statuses.contains(CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED)) {
      return CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED;
    }
    if (statuses.contains(CriticalClaimPreflightStatus.VERIFIED_REFUTED)) {
      return CriticalClaimPreflightStatus.VERIFIED_REFUTED;
    }
    if (support) {
      return CriticalClaimPreflightStatus.VERIFIED_SUPPORTED;
    }
    if (statuses.contains(CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE)) {
      return CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE;
    }
    return spec.computationContractId().isBlank()
        ? CriticalClaimPreflightStatus.UNKNOWN
        : CriticalClaimPreflightStatus.UNTESTABLE;
  }

  private static String statusDetail(
      CriticalClaimPreflightStatus status, List<CriticalClaimPreflightEvidence> evidence) {
    if (evidence.isEmpty()) {
      return status == CriticalClaimPreflightStatus.UNKNOWN
          ? "NO_TRUSTED_EVIDENCE"
          : "NO_REGISTERED_COMPUTATION_CONTRACT";
    }
    return evidence.stream()
        .map(CriticalClaimPreflightEvidence::detail)
        .filter(value -> !value.isBlank())
        .distinct()
        .sorted()
        .collect(java.util.stream.Collectors.joining(";"));
  }
}
