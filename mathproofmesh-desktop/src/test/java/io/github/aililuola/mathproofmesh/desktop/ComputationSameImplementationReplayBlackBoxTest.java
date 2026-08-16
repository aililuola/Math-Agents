package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.HandlerEvidence;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputationSameImplementationReplayBlackBoxTest {
  @Test
  void stableButInvalidProducerOutputIsNotIndependentVerification() {
    ExternalComputationHandler erroneousProducer = new StableErroneousProducer();
    ComputationBroker broker =
        ComputationIssue010BlackBoxFixtures.broker(
            "stable-error", new ComputationHandlerRegistry(erroneousProducer));
    ExperimentSpec request =
        ComputationIssue010BlackBoxFixtures.spec(
            "stable-invalid-certificate",
            ComputationMethod.SYMPY_SIMPLIFY,
            "{\"expression\":\"1 + 1\"}");
    ComputationBroker.PreparedDecision prepared =
        broker.decide(request, ComputationContext.initial("route-010", 8));
    ExperimentResult recorded =
        broker.runExperiment(prepared.spec(), prepared.decision());
    ComputationBroker.ComputationAudit audit =
        broker.auditExperiment(prepared.spec(), prepared.decision(), null, recorded);

    System.out.println("GENERATOR_RESULTS_MATCH=" + (audit.valid() ? 1 : 0));
    System.out.println("INVALID_CERTIFICATE_ACCEPTED=" + (audit.valid() ? 1 : 0));
    System.out.println("AUDIT_VALID=" + audit.valid());
    assertThat(audit.valid()).isFalse();
  }

  private static final class StableErroneousProducer implements ExternalComputationHandler {
    @Override
    public boolean supports(ComputationMethod method) {
      return method == ComputationMethod.SYMPY_SIMPLIFY;
    }

    @Override
    public String toolIdentity(ComputationMethod method) {
      return "stable-erroneous-producer/1";
    }

    @Override
    public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
      return new HandlerEvidence(
          ExperimentOutcome.CERTIFIED,
          EvidenceStrength.FORMAL_CERTIFICATE,
          ComputationIssue010BlackBoxFixtures.object("{\"complete_domain\":true}"),
          null,
          ComputationIssue010BlackBoxFixtures.object("{\"value\":\"1 + 1 = 3\"}"),
          true,
          1,
          true,
          List.of("The producer repeats the same invalid certificate."),
          null);
    }
  }
}
