package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.Collection;
import java.util.function.Supplier;

/** Structural verification gates the more expensive detailed review. */
public final class VerificationPipeline {

  public Result verify(
      Collection<String> authorAgentIds,
      String reviewerAgentId,
      Supplier<VerificationReport> structural,
      Supplier<VerificationReport> detailed) {
    ReviewIsolationPolicy.requireIndependent(authorAgentIds, reviewerAgentId);
    VerificationReport structuralReport =
        java.util.Objects.requireNonNull(structural, "structural").get();
    if (structuralReport == null
        || structuralReport.verdict() != VerificationVerdict.PASS) {
      return new Result(structuralReport, null, false);
    }
    VerificationReport detailedReport =
        java.util.Objects.requireNonNull(detailed, "detailed").get();
    boolean passed =
        detailedReport != null
            && detailedReport.verdict() == VerificationVerdict.PASS;
    return new Result(structuralReport, detailedReport, passed);
  }

  public record Result(
      VerificationReport structuralReport,
      VerificationReport detailedReport,
      boolean passed) {

    public boolean detailedExecuted() {
      return detailedReport != null;
    }
  }
}
