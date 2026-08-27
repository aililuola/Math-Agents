package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import org.junit.jupiter.api.Test;

class ComputationCapabilityCatalogDriftBlackBoxTest {
  @Test
  void nativeNumberTheoryCapabilityIsAcceptedByThePublicToolContract() {
    boolean nativeSupported =
        ComputationHandlerRegistry.javaOnly().supports(ComputationMethod.NUMBER_THEORY_CHECK);
    boolean contractAccepted;
    try {
      new ToolRequest(
          ComputationIssue010BlackBoxFixtures.object(
              "{\"operation\":\"is_prime\",\"value\":17}"),
          ComputationIssue010BlackBoxFixtures.object("{}"),
          "number_theory_check",
          100,
          "Check whether 17 is prime.",
          "catalog-drift");
      contractAccepted = true;
    } catch (IllegalArgumentException exception) {
      contractAccepted = false;
    }

    System.out.println("NATIVE_HANDLER_SUPPORTED=" + (nativeSupported ? 1 : 0));
    System.out.println("TOOL_REQUEST_CONTRACT_ACCEPTED=" + (contractAccepted ? 1 : 0));
    System.out.println(
        "CAPABILITY_CATALOG_DRIFT=" + (nativeSupported && !contractAccepted ? 1 : 0));
    assertThat(nativeSupported).isTrue();
    assertThat(contractAccepted).isTrue();
  }
}
