package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComputationExecutionLedgerTest {
  @Test
  void ledgerRecordsExactlyOnceProducerVerifierAndAuthorityTransitions() {
    var broker = ComputationFixtures.broker("ledger-counts");
    ComputationIssue010TestSupport.run(broker, ComputationIssue010TestSupport.linearAlgebraSpec());
    var record = broker.executionService().executions().records().getFirst();
    assertThat(record.status()).isEqualTo(ComputationExecutionStatus.AUTHORITY_APPLIED);
    assertThat(record.producerExecutions()).isEqualTo(1);
    assertThat(record.verifierExecutions()).isEqualTo(1);
    assertThat(record.authorityProjections()).isEqualTo(1);
  }
}
