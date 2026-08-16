package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import org.junit.jupiter.api.Test;

class DesktopComputationResultArtifactMissingBlackBoxTest {
  @Test
  void nativeResultCarriesDurableResultCertificateAndVerificationArtifacts() {
    ExperimentResult result =
        ComputationIssue010BlackBoxFixtures.run(
            ComputationIssue010BlackBoxFixtures.broker(
                "artifact-missing", ComputationHandlerRegistry.javaOnly()),
            ComputationIssue010BlackBoxFixtures.graphSpec("graph-artifacts"));

    int resultRefs =
        (int)
            result.artifactRefs().stream()
                .filter(ref -> ref.section().equals("computation-result"))
                .count();
    int certificateRefs =
        (int)
            result.artifactRefs().stream()
                .filter(ref -> ref.section().equals("computation-certificate"))
                .count();
    int receiptRefs =
        (int)
            result.artifactRefs().stream()
                .filter(ref -> ref.section().equals("computation-verification_receipt"))
                .count();
    System.out.println("EXECUTED_RESULTS=1");
    System.out.println("RESULT_ARTIFACT_REFS=" + resultRefs);
    System.out.println("CERTIFICATE_ARTIFACT_REFS=" + certificateRefs);
    System.out.println("VERIFICATION_RECEIPT_REFS=" + receiptRefs);
    assertThat(resultRefs).isEqualTo(1);
    assertThat(certificateRefs).isEqualTo(1);
    assertThat(receiptRefs).isEqualTo(1);
  }
}
