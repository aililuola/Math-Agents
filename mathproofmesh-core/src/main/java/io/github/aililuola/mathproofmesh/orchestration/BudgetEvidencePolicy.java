package io.github.aililuola.mathproofmesh.orchestration;

/** Frozen public-evidence scoring policy for deterministic budget decisions. */
public record BudgetEvidencePolicy(
    boolean forceWidenWhenAllFailed,
    int maxExecutionRepairs,
    int maxStructuralRepairs,
    int maxUnknownRepairs,
    boolean allowStrategyFailureRepair,
    double meaningfulProgressThreshold,
    double unverifiedProgressDiscount,
    double uncertainProgressDiscount,
    double failedProgressDiscount,
    double structuralFailureProgressCap,
    double executionFailureProgressCap,
    double strategyFailureProgressCap,
    double structuralFailurePenalty,
    double executionFailurePenalty,
    double strategyFailurePenalty,
    double repeatedFailurePenalty) {

  public BudgetEvidencePolicy {
    if (maxExecutionRepairs < 0 || maxStructuralRepairs < 0 || maxUnknownRepairs < 0) {
      throw new IllegalArgumentException("repair limits must not be negative");
    }
    checkUnit(meaningfulProgressThreshold, "meaningfulProgressThreshold");
    checkUnit(unverifiedProgressDiscount, "unverifiedProgressDiscount");
    checkUnit(uncertainProgressDiscount, "uncertainProgressDiscount");
    checkUnit(failedProgressDiscount, "failedProgressDiscount");
    checkUnit(structuralFailureProgressCap, "structuralFailureProgressCap");
    checkUnit(executionFailureProgressCap, "executionFailureProgressCap");
    checkUnit(strategyFailureProgressCap, "strategyFailureProgressCap");
    checkNonnegative(structuralFailurePenalty, "structuralFailurePenalty");
    checkNonnegative(executionFailurePenalty, "executionFailurePenalty");
    checkNonnegative(strategyFailurePenalty, "strategyFailurePenalty");
    checkNonnegative(repeatedFailurePenalty, "repeatedFailurePenalty");
  }

  public static BudgetEvidencePolicy defaults() {
    return new BudgetEvidencePolicy(
        true,
        1,
        1,
        1,
        false,
        0.04d,
        0.55d,
        0.4d,
        0.1d,
        0.1d,
        0.45d,
        0.05d,
        0.3d,
        0.12d,
        0.5d,
        0.15d);
  }

  public boolean repairAllowed(PathBudgetStats path) {
    int limit =
        switch (path.failureLevel()) {
          case NONE -> 0;
          case EXECUTION -> maxExecutionRepairs;
          case STRUCTURAL -> maxStructuralRepairs;
          case STRATEGY -> allowStrategyFailureRepair ? maxUnknownRepairs : 0;
          case PROBLEM_INTEGRITY -> 0;
        };
    return path.failedRepairAttempts() < limit;
  }

  public double adjustedProgress(PathBudgetStats path) {
    double progress = path.marginalProgress();
    if (!path.verifiedProgress()) {
      progress *= unverifiedProgressDiscount;
    }
    if ("uncertain".equals(path.latestVerdict()) || "unknown".equals(path.latestVerdict())) {
      progress *= uncertainProgressDiscount;
    }
    if (!path.failed()) {
      return progress;
    }
    progress *= failedProgressDiscount;
    double cap =
        switch (path.failureLevel()) {
          case STRUCTURAL, PROBLEM_INTEGRITY -> structuralFailureProgressCap;
          case EXECUTION -> executionFailureProgressCap;
          case STRATEGY -> strategyFailureProgressCap;
          case NONE -> 1.0d;
        };
    return Math.min(progress, cap);
  }

  public double failurePenalty(PathBudgetStats path) {
    if (!path.failed()) {
      return 0.0d;
    }
    double base =
        switch (path.failureLevel()) {
          case STRUCTURAL, PROBLEM_INTEGRITY -> structuralFailurePenalty;
          case EXECUTION -> executionFailurePenalty;
          case STRATEGY -> strategyFailurePenalty;
          case NONE -> 0.0d;
        };
    return base + repeatedFailurePenalty * path.consecutiveFailures();
  }

  private static void checkUnit(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(field + " must be within [0,1]");
    }
  }

  private static void checkNonnegative(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d) {
      throw new IllegalArgumentException(field + " must be finite and nonnegative");
    }
  }
}
