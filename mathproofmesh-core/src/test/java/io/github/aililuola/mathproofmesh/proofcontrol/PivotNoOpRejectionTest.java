package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PivotNoOpRejectionTest {
  @Test
  void textOnlyAndCoreIdeaAppendOnlyDeltasFailClosed() {
    PivotDeltaAudit audit =
        new SemanticPivotDeterministicAuditor()
            .audit(
                SemanticPivotTestFixtures.textOnlyDelta(),
                SemanticPivotTestFixtures.sourceSignature(),
                SemanticPivotTestFixtures.textOnlySignature(),
                SemanticPivotTestFixtures.authority());

    assertThat(audit.failureCodes())
        .contains(
            SemanticPivotDeterministicAuditor.EMPTY_SEMANTIC_DELTA,
            SemanticPivotDeterministicAuditor.TEXT_ONLY_REVISION,
            SemanticPivotDeterministicAuditor.CORE_IDEA_APPEND_ONLY);
  }
}
