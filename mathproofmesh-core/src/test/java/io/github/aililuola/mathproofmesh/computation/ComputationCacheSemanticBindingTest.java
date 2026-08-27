package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComputationCacheSemanticBindingTest {
  @Test
  void canonicalCacheKeyIncludesRunCapabilityAndVerifierVersions() {
    var cache = new InMemoryComputationCache();
    var broker = ComputationFixtures.broker("cache-source");
    var outcome =
        ComputationIssue010TestSupport.run(
            broker,
            ComputationIssue010TestSupport.linearAlgebraSpec());
    var raw =
        broker.executionService().artifacts()
            .read(outcome.artifacts().result().reference(), ComputationResultArtifact.class)
            .orElseThrow();
    var key = new ComputationCacheKey("run-a", "exec", "cap", "1", "producer", "verifier-1", "schema", "runtime");
    cache.put(key, new CanonicalComputationCacheEntry(raw, outcome.certificate()));
    assertThat(cache.find(key)).isPresent();
    assertThat(cache.find(new ComputationCacheKey("run-a", "exec", "cap", "1", "producer", "verifier-2", "schema", "runtime")))
        .isEmpty();
  }
}
