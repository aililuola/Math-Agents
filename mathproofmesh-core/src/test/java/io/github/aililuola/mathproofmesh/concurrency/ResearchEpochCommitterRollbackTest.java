package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ResearchEpochCommitterRollbackTest {
  @Test
  void secondMutationFailureRestoresTheWholeAuthorityBatch() {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    ResearchMergePlan plan =
        new ResearchMergePlan(
            frozen.epochId(),
            frozen.snapshotHash(),
            List.of(
                new ResearchMergeDecision("work-a", "result-a", true, "accepted", 0),
                new ResearchMergeDecision("work-b", "result-b", true, "accepted", 1)));
    List<String> authorityState = new ArrayList<>();
    AtomicInteger restores = new AtomicInteger();
    ResearchAuthorityMutationTransaction<List<String>> transaction =
        new ResearchAuthorityMutationTransaction<>() {
          @Override
          public List<String> snapshot() {
            return List.copyOf(authorityState);
          }

          @Override
          public ResearchAuthorityMutationReceipt apply(List<String> acceptedResultHashes) {
            authorityState.add(acceptedResultHashes.get(0));
            throw new IllegalStateException("second authority mutation failed");
          }

          @Override
          public void restore(List<String> snapshot) {
            authorityState.clear();
            authorityState.addAll(snapshot);
            restores.incrementAndGet();
          }
        };

    assertThatThrownBy(
            () ->
                new ResearchEpochCommitter()
                    .commit(frozen, plan, frozen::authority, transaction))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("second authority mutation failed");

    assertThat(authorityState).as("AUTHORITY_STATE_CHANGES").isEmpty();
    assertThat(restores).hasValue(1);
  }

  @Test
  void forgedMutationReceiptAlsoRollsBack() {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    ResearchMergePlan plan =
        new ResearchMergePlan(
            frozen.epochId(),
            frozen.snapshotHash(),
            List.of(new ResearchMergeDecision("work-a", "result-a", true, "accepted", 0)));
    List<String> authorityState = new ArrayList<>();

    assertThatThrownBy(
            () ->
                new ResearchEpochCommitter()
                    .commit(
                        frozen,
                        plan,
                        frozen::authority,
                        new ResearchAuthorityMutationTransaction<List<String>>() {
                          @Override
                          public List<String> snapshot() {
                            return List.copyOf(authorityState);
                          }

                          @Override
                          public ResearchAuthorityMutationReceipt apply(
                              List<String> acceptedResultHashes) {
                            authorityState.add("partial");
                            return ResearchAuthorityMutationReceipt.create(
                                frozen.epochId(),
                                "wrong-plan",
                                frozen.authority().stableHash(),
                                "after",
                                acceptedResultHashes,
                                List.of(),
                                List.of(),
                                List.of());
                          }

                          @Override
                          public void restore(List<String> snapshot) {
                            authorityState.clear();
                            authorityState.addAll(snapshot);
                          }
                        }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not bind");
    assertThat(authorityState).isEmpty();
  }
}
