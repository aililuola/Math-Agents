package io.github.aililuola.mathproofmesh.strategydiversity;

public record StrategyCandidateRecord(
    String strategyId,
    String episodeId,
    StrategyCandidateStatus status,
    String mechanismSignatureHash,
    String preflightReportHash,
    Double calibratedScore,
    String detail,
    int captureOrder,
    long version) {
  public StrategyCandidateRecord {
    strategyId = StrategySemanticNormalizer.require(strategyId, "strategyId");
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    status = java.util.Objects.requireNonNull(status, "status");
    mechanismSignatureHash =
        mechanismSignatureHash == null ? "" : mechanismSignatureHash.strip();
    preflightReportHash = preflightReportHash == null ? "" : preflightReportHash.strip();
    if (calibratedScore != null
        && (!Double.isFinite(calibratedScore)
            || calibratedScore < 0.0d
            || calibratedScore > 1.0d)) {
      throw new IllegalArgumentException("calibratedScore must be in [0,1]");
    }
    detail = detail == null ? "" : detail.strip();
    if (captureOrder < 0 || version < 1L) {
      throw new IllegalArgumentException("candidate order and version are invalid");
    }
  }
}
