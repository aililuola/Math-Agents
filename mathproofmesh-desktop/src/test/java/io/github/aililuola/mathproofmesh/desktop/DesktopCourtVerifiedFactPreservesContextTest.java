package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopCourtVerifiedFactPreservesContextTest {
  @TempDir Path temporaryDirectory;

  @Test
  void courtFactPreservesTheFrozenQuantifierBindingAndScope() throws Exception {
    String claimId = "claim-fact-context";
    QuantifierSpec quantifier =
        new QuantifierSpec("x", "finite set", "forall", 0, List.of(), "fact-x");
    VariableBinding variable =
        new VariableBinding(List.of("x"), "x", "finite set", "critical-claim", "fact-x");
    CriticalClaim critical =
        new CriticalClaim(
            claimId,
            List.of(),
            "Verify the claim in its declared finite scope.",
            "required",
            null,
            "P(x)",
            "needs_check");
    CriticalClaimContextBinding binding =
        new CriticalClaimContextBinding(
            claimId,
            "@claim",
            List.of(),
            List.of("H"),
            List.of(quantifier),
            List.of(variable),
            List.of("finite-domain-D"),
            "positive");

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "fact-context")) {
      harness.freezeAndCreateRoute(
          DesktopClaimSalvageTestHarness.strategyWithCriticalClaim(
              "strategy-fact-context", critical, binding));
      harness.installSingleClaimRound(0, claimId, "P(x)");
      harness.integrateInstalledRound();

      var frozen = harness.frozenClaim(claimId);
      var fact = harness.typedMemory().find(claimId).orElseThrow();
      int quantifierLosses = fact.quantifiers().equals(frozen.quantifiers()) ? 0 : 1;
      int bindingLosses = fact.variableBindings().equals(frozen.variableBindings()) ? 0 : 1;
      int scopeLosses = fact.scopeLimitations().equals(frozen.scopeLimitations()) ? 0 : 1;
      int polarityLosses = fact.polarity().equals(frozen.polarity()) ? 0 : 1;
      assertThat(fact.assumptions()).isEqualTo(frozen.assumptions());
      assertThat(fact.quantifiers()).isEqualTo(frozen.quantifiers());
      assertThat(fact.variableBindings()).isEqualTo(frozen.variableBindings());
      assertThat(fact.scopeLimitations()).isEqualTo(frozen.scopeLimitations());
      assertThat(fact.quantifiers()).anyMatch(value -> value.variableId().equals("fact-x"));
      assertThat(fact.variableBindings()).anyMatch(value -> value.variableId().equals("fact-x"));
      assertThat(fact.claimStatementHash()).isEqualTo(frozen.claimStatementHash());
      assertThat(fact.claimSemanticHash()).isEqualTo(frozen.claimSemanticHash());
      assertThat(fact.polarity()).isEqualTo(frozen.polarity());
      System.out.println("VERIFIED_FACT_QUANTIFIER_LOSSES=" + quantifierLosses);
      System.out.println("VERIFIED_FACT_BINDING_LOSSES=" + bindingLosses);
      System.out.println("VERIFIED_FACT_SCOPE_LOSSES=" + scopeLosses);
      System.out.println("VERIFIED_FACT_POLARITY_LOSSES=" + polarityLosses);
    }
  }
}
