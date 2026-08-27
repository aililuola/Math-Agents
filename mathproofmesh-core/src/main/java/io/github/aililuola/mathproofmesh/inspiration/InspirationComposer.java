package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComposedInspiration;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationReview;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Composes only independently reviewed, complementary, falsifiable sources. */
public final class InspirationComposer {
  private final InspirationPolicy.ComposerRules rules;

  public InspirationComposer(InspirationPolicy.ComposerRules rules) {
    this.rules = java.util.Objects.requireNonNull(rules, "rules");
  }

  public Optional<ComposedInspiration> compose(
      List<InspirationProposal> candidates,
      Map<String, InspirationReview> reviews,
      Set<String> openObligationIds) {
    if (rules.maxCandidatesPerRound() == 0) {
      return Optional.empty();
    }
    List<InspirationProposal> eligible =
        (candidates == null ? List.<InspirationProposal>of() : candidates).stream()
            .filter(item -> independentlyAccepted(item, reviews))
            .filter(item -> connected(item, openObligationIds))
            .limit(rules.maxCandidatesPerRound())
            .toList();
    for (int left = 0; left < eligible.size(); left++) {
      List<InspirationProposal> selected = new ArrayList<>();
      selected.add(eligible.get(left));
      for (int right = left + 1;
          right < eligible.size() && selected.size() < rules.maxSources();
          right++) {
        InspirationProposal candidate = eligible.get(right);
        if (complementary(selected, candidate)) {
          selected.add(candidate);
        }
      }
      if (selected.size() < 2) {
        continue;
      }
      int cost = selected.stream().mapToInt(InspirationProposal::estimatedCost).sum();
      if (cost > rules.maxCombinedCost()) {
        continue;
      }
      List<String> fastTests =
          selected.stream().flatMap(item -> fastTests(item).stream()).distinct().toList();
      if (rules.requireQuickFalsification() && fastTests.isEmpty()) {
        continue;
      }
      Set<String> obligations = new LinkedHashSet<>();
      selected.forEach(
          item -> {
            obligations.addAll(item.noveltySignature().targetedObligationIds());
            obligations.addAll(item.generatedObligations());
          });
      List<String> mechanisms =
          selected.stream().map(item -> item.mechanism().value()).distinct().toList();
      NoveltySignature signature =
          new NoveltySignature(
              selected.stream()
                  .flatMap(item -> item.noveltySignature().coreObjects().stream())
                  .distinct()
                  .toList(),
              List.of(),
              selected.stream()
                  .flatMap(item -> item.noveltySignature().keyTransformations().stream())
                  .distinct()
                  .toList(),
              mechanisms,
              null,
              null,
              null,
              selected.stream()
                  .flatMap(item -> item.noveltySignature().proofPrinciples().stream())
                  .distinct()
                  .toList(),
              Map.of(),
              selected.stream()
                  .flatMap(item -> item.noveltySignature().representationTags().stream())
                  .distinct()
                  .toList(),
              List.copyOf(obligations));
      List<String> sourceIds = selected.stream().map(InspirationProposal::proposalId).toList();
      String id =
          "composition_"
              + CanonicalJson.stableHash(List.of(sourceIds, mechanisms, obligations))
                  .substring(0, 16);
      return Optional.of(
          new ComposedInspiration(
              mechanisms,
              List.of("all source reviews remain valid", "scope and target links are preserved"),
              id,
              cost,
              fastTests,
              "execute the lowest-cost fast failure test before route admission",
              selected.stream()
                  .flatMap(item -> item.generatedObligations().stream())
                  .distinct()
                  .toList(),
              signature,
              sourceIds,
              List.copyOf(obligations)));
    }
    return Optional.empty();
  }

  private static boolean independentlyAccepted(
      InspirationProposal proposal, Map<String, InspirationReview> reviews) {
    InspirationReview review =
        reviews == null ? null : reviews.get(proposal.proposalId());
    return review != null
        && !review.reviewerAgentId().equals(proposal.sourceAgentId())
        && review.reviewStatus().equals("completed")
        && !review.recommendation().equals("reject")
        && review.semanticallyDistinct()
        && review.relevantToOpenObligation()
        && review.internallyCoherent();
  }

  private static boolean connected(
      InspirationProposal proposal, Set<String> openObligationIds) {
    Set<String> open = openObligationIds == null ? Set.of() : openObligationIds;
    return proposal.noveltySignature().targetedObligationIds().stream().anyMatch(open::contains)
        || proposal.generatedObligations().stream().anyMatch(open::contains);
  }

  private static boolean complementary(
      List<InspirationProposal> selected, InspirationProposal candidate) {
    return selected.stream().noneMatch(item -> item.mechanism() == candidate.mechanism());
  }

  private static List<String> fastTests(InspirationProposal proposal) {
    if (proposal.construction() != null) {
      return proposal.construction().falsificationTests();
    }
    if (proposal.representation() != null) {
      return proposal.representation().fastFailureTests();
    }
    if (proposal.mutation() != null) {
      return proposal.mutation().fastFailureTests();
    }
    if (proposal.composition() != null) {
      return proposal.composition().fastFailureTests();
    }
    return List.of();
  }
}
