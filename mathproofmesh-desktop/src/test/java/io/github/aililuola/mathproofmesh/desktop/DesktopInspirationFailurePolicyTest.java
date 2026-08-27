package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.agent.StructuredOutputError;
import io.github.aililuola.mathproofmesh.provider.AgentCallFailure;
import io.github.aililuola.mathproofmesh.provider.ProviderException;
import org.junit.jupiter.api.Test;

final class DesktopInspirationFailurePolicyTest {
  @Test
  void skipsOneExhaustedProviderCallWithoutAbortingTheInspirationRound() {
    AgentCallFailure failure =
        new AgentCallFailure(
            "construction-inventor", ProviderException.network(new java.io.IOException()), 2);

    assertTrue(DesktopSolveCoordinator.isRecoverableInspirationAgentFailure(failure));
  }

  @Test
  void skipsOneInvalidStructuredProposalWithoutMaterializingIt() {
    assertTrue(
        DesktopSolveCoordinator.isRecoverableInspirationAgentFailure(
            new StructuredOutputError("invalid proposal")));
  }

  @Test
  void preservesUserCancellationAsARunLevelStopSignal() {
    AgentCallFailure failure =
        new AgentCallFailure("construction-inventor", ProviderException.cancelled(), 0);

    assertFalse(DesktopSolveCoordinator.isRecoverableInspirationAgentFailure(failure));
  }
}
