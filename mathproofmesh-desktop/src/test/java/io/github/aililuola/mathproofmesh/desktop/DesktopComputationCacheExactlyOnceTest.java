package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationCacheExactlyOnceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void cacheHitCreatesReceiptsWithoutRerunningTheProducer() {
    var broker = DesktopComputationIssue010Support.broker("cache-once", temporaryDirectory, new InMemoryComputationCache());
    DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.finiteMap("first"), "route-a", 0);
    var cached = DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.finiteMap("second"), "route-b", 1);
    var records = broker.executionService().executions().records();
    assertThat(cached.cacheHit()).isTrue();
    assertThat(records).hasSize(2);
    assertThat(records.stream().mapToInt(record -> record.producerExecutions()).sum()).isEqualTo(1);
    assertThat(records.stream().mapToInt(record -> record.verifierExecutions()).sum()).isEqualTo(2);
    assertThat(records.stream().mapToInt(record -> record.authorityProjections()).sum()).isEqualTo(2);
  }
}
