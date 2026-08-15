package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopComputationEvidenceFullContextBindingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void computationCapabilityRequiresEveryFrozenContextDimension() throws Exception {
    var frozen =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("frozen"),
            "computation-context-frozen",
            "forall",
            List.of("x"),
            List.of("H"),
            List.of("scope-D"),
            "positive");
    var domains = JsonNodeFactory.instance.objectNode().put("x", "finite set D");
    var exact = DesktopClaimEvidenceTestSupport.binding(frozen, domains);

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("issuer"), "computation-context-issuer")) {
      int exactCapabilities = issue(harness, frozen, exact).size();

      ArrayList<QuantifierSpec> quantifiers = new ArrayList<>(exact.quantifiers());
      QuantifierSpec original = quantifiers.getLast();
      quantifiers.set(
          quantifiers.size() - 1,
          new QuantifierSpec(
              original.displayName(),
              original.domain(),
              "exists",
              original.order(),
              original.restrictions(),
              original.variableId()));
      ClaimEvidenceSemanticBinding wrongQuantifier =
          copy(exact, quantifiers, exact.scopeLimitations(), exact.polarity());
      int quantifierMismatchAccepts = issue(harness, frozen, wrongQuantifier).size();

      ClaimEvidenceSemanticBinding wrongScope =
          copy(exact, exact.quantifiers(), List.of("scope-E"), exact.polarity());
      int scopeMismatchAccepts = issue(harness, frozen, wrongScope).size();

      ClaimEvidenceSemanticBinding wrongPolarity =
          copy(exact, exact.quantifiers(), exact.scopeLimitations(), "negative");
      int polarityMismatchAccepts = issue(harness, frozen, wrongPolarity).size();
      var exactSpec = DesktopClaimEvidenceTestSupport.spec(exact);
      int missingResultBindingAccepts =
          harness
              .trustedComputationEvidence(
                  exactSpec,
                  DesktopClaimEvidenceTestSupport.resultWithoutBinding(
                      exactSpec, exact, exact.claimId()),
                  frozen)
              .size();

      assertThat(exactCapabilities).isOne();
      assertThat(quantifierMismatchAccepts).isZero();
      assertThat(scopeMismatchAccepts).isZero();
      assertThat(polarityMismatchAccepts).isZero();
      assertThat(missingResultBindingAccepts).isZero();
      System.out.println("EXACT_CONTEXT_COMPUTATION_CAPABILITIES=" + exactCapabilities);
      System.out.println("QUANTIFIER_MISMATCH_EVIDENCE_ACCEPTS=" + quantifierMismatchAccepts);
      System.out.println("SCOPE_MISMATCH_EVIDENCE_ACCEPTS=" + scopeMismatchAccepts);
      System.out.println("POLARITY_MISMATCH_EVIDENCE_ACCEPTS=" + polarityMismatchAccepts);
      System.out.println("MISSING_RESULT_BINDING_EVIDENCE_ACCEPTS=" + missingResultBindingAccepts);
    }
  }

  private static List<
          io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.TrustedClaimEvidence>
      issue(
      DesktopClaimSalvageTestHarness harness,
      io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot frozen,
      ClaimEvidenceSemanticBinding binding) {
    var spec = DesktopClaimEvidenceTestSupport.spec(binding);
    var result = DesktopClaimEvidenceTestSupport.result(spec, binding, binding.claimId());
    return harness.trustedComputationEvidence(spec, result, frozen);
  }

  private static ClaimEvidenceSemanticBinding copy(
      ClaimEvidenceSemanticBinding source,
      List<QuantifierSpec> quantifiers,
      List<String> scope,
      String polarity) {
    return new ClaimEvidenceSemanticBinding(
        source.problemHash(),
        source.claimId(),
        source.claimStatementHash(),
        source.claimSemanticHash(),
        source.statement(),
        source.conclusion(),
        source.assumptions(),
        quantifiers,
        source.variableBindings(),
        scope,
        polarity,
        source.dependencyClaimIds(),
        source.computationDomains());
  }
}
