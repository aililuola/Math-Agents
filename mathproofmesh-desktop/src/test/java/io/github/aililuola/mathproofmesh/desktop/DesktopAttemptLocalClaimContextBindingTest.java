package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimSemanticContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopAttemptLocalClaimContextBindingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void attemptLocalClaimUsesItsOwnExplicitContext() throws Exception {
    String claimId = "attempt-local-bound";
    QuantifierSpec quantifier =
        new QuantifierSpec("y", "local domain", "exists", 0, List.of(), "local-y");
    VariableBinding variable =
        new VariableBinding(List.of("y"), "y", "local domain", "attempt-local", "local-y");
    ClaimSemanticContextBinding binding =
        new ClaimSemanticContextBinding(
            claimId,
            "@claim",
            List.of("local-H"),
            List.of(quantifier),
            List.of(variable),
            List.of("local-scope"),
            "negative");

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "attempt-local-bound")) {
      harness.freezeAndCreateRoute();
      harness.installModernLocalClaimRound(0, claimId, "P(y)", binding);
      harness.integrateInstalledRound();

      var frozen = harness.frozenClaim(claimId);
      assertThat(frozen.assumptions()).contains("local-H");
      assertThat(frozen.quantifiers()).containsExactly(quantifier);
      assertThat(frozen.variableBindings()).containsExactly(variable);
      assertThat(frozen.scopeLimitations()).contains("local-scope");
      assertThat(frozen.scopeLimitations())
          .doesNotContain("LEGACY_INCOMPLETE_SEMANTIC_CONTEXT");
      assertThat(frozen.polarity()).isEqualTo("negative");
      long courtCases =
          harness.claimCourt().records().stream()
              .filter(record -> record.frozenClaim().claimId().equals(claimId))
              .count();
      assertThat(courtCases).isOne();
      System.out.println("BOUND_LOCAL_CLAIM_COURT_CASES=" + courtCases);
    }
  }
}
