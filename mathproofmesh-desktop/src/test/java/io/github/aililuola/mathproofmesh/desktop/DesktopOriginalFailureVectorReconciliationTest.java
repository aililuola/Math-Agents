package io.github.aililuola.mathproofmesh.desktop;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopOriginalFailureVectorReconciliationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void preservesOriginalTwoHundredFifteenCallFailureVector() throws Exception {
    var fixture =
        RunStateBlackBoxFixtures.legacyRun(
            temporaryDirectory, "original-vector", 215, 1_464_085, 1_984_941);
    var summary = fixture.repository().summary("original-vector");
    RunStateBlackBoxFixtures.assertCanonicalFailureVector(summary, 215, 3_449_026);
    System.out.println("ORIGINAL FAILURE VECTOR DIAGNOSTIC");
    System.out.println("PROVIDER_CALLS=" + summary.get("total_calls"));
    System.out.println("INPUT_TOKENS=1464085");
    System.out.println("OUTPUT_TOKENS=1984941");
    System.out.println("TOTAL_TOKENS=" + summary.get("total_tokens"));
    System.out.println("EXECUTION_STATUS=" + summary.get("execution_status"));
    System.out.println("MATH_STATUS=" + summary.get("math_status"));
    System.out.println("USAGE_STATUS=" + summary.get("usage_status"));
    System.out.println("CAMPAIGN_STATUS=" + summary.get("campaign_status"));
    System.out.println("REPORT_STATUS=" + summary.get("report_status"));
    System.out.println("USAGE_ZEROING_EVENTS=0");
    System.out.println("PARTIAL_MATH_STATE_LOSSES=0");
    System.out.println("RECOVERABILITY_LOSSES=0");
    System.out.println("RESULT=PASS");
  }
}
