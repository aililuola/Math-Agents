package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.HandlerEvidence;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationArtifactStore;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DesktopComputationHardCrashDuplicateExecutionBlackBoxTest {
  @Test
  void completedProducerIsNotRepeatedAfterAProcessTermination() {
    AtomicInteger producerExecutions = new AtomicInteger();
    ExternalComputationHandler producer = new CountingProducer(producerExecutions);
    ExperimentSpec request =
        ComputationIssue010BlackBoxFixtures.spec(
            "hard-crash-request",
            ComputationMethod.SYMPY_SIMPLIFY,
            "{\"expression\":\"x + 0\"}");
    InMemoryComputationArtifactStore artifacts = new InMemoryComputationArtifactStore();
    AtomicReference<io.github.aililuola.mathproofmesh.computation.ComputationExecutionState>
        durableState = new AtomicReference<>();

    try {
      ComputationBroker first = new ComputationBroker(
          "hard-crash-run",
          io.github.aililuola.mathproofmesh.computation.ComputationLimits.defaultsEnabled(),
          new ComputationHandlerRegistry(producer),
          new io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache(),
          artifacts);
      first.setStatePersister((reason, state) -> durableState.set(state));
      ComputationIssue010BlackBoxFixtures.run(first, request);
      throw new SimulatedProcessTermination();
    } catch (SimulatedProcessTermination expected) {
      // A fresh process only sees durable computation state.
    }

    ComputationBroker restored = new ComputationBroker(
        "hard-crash-run",
        io.github.aililuola.mathproofmesh.computation.ComputationLimits.defaultsEnabled(),
        new ComputationHandlerRegistry(producer),
        new io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache(),
        artifacts);
    restored.restore(durableState.get());
    ComputationIssue010BlackBoxFixtures.run(restored, request);

    System.out.println("LOGICAL_REQUESTS=1");
    System.out.println("HANDLER_EXECUTIONS=" + producerExecutions.get());
    System.out.println("EXPECTED_HANDLER_EXECUTIONS=1");
    assertThat(producerExecutions).hasValue(1);
  }

  private static final class SimulatedProcessTermination extends Error {
    private static final long serialVersionUID = 1L;
  }

  private record CountingProducer(AtomicInteger executions)
      implements ExternalComputationHandler {
    @Override
    public boolean supports(ComputationMethod method) {
      return method == ComputationMethod.SYMPY_SIMPLIFY;
    }

    @Override
    public String toolIdentity(ComputationMethod method) {
      return "counting-producer/1";
    }

    @Override
    public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
      executions.incrementAndGet();
      return new HandlerEvidence(
          ExperimentOutcome.CERTIFIED,
          EvidenceStrength.FORMAL_CERTIFICATE,
          ComputationIssue010BlackBoxFixtures.object("{\"complete_domain\":true}"),
          null,
          ComputationIssue010BlackBoxFixtures.object("{\"value\":\"x\"}"),
          true,
          1,
          true,
          List.of("stable producer output"),
          null);
    }
  }
}
