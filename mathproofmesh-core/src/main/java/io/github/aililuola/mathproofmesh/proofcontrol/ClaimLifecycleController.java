package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks claim authority monotonically while leaving memory admission to its
 * owning service.
 */
public final class ClaimLifecycleController {
  public enum State {
    PROPOSED,
    LOCALLY_VERIFIED,
    INDEPENDENTLY_VERIFIED,
    REFEREE_ACCEPTED,
    FACT_CANDIDATE,
    EXTERNALLY_ADMITTED_FACT,
    INVALIDATED,
    REJECTED
  }

  public record Entry(
      String claimId,
      String sourceAttemptId,
      String sourceDeltaId,
      State state,
      List<ProofControlModels.DependencyRef> dependencies,
      List<String> localReportIds,
      List<String> independentReportIds,
      List<String> refereeReviewIds,
      boolean sourceAttemptIncomplete,
      AttemptArtifactKind claimKind,
      AttemptStatus sourceAttemptStatus,
      String sourceRouteStatus,
      String invalidationReason,
      List<String> invalidatingEvidenceIds,
      List<String> history) {
    public Entry {
      dependencies = List.copyOf(dependencies);
      localReportIds = List.copyOf(localReportIds);
      independentReportIds = List.copyOf(independentReportIds);
      refereeReviewIds = List.copyOf(refereeReviewIds);
      invalidatingEvidenceIds = List.copyOf(invalidatingEvidenceIds);
      history = List.copyOf(history);
    }
  }

  public record PromotionProposal(
      String id,
      String claimId,
      boolean eligible,
      boolean writesFact,
      List<String> requiredAuthorityServices,
      List<String> reasons) {
    public PromotionProposal {
      requiredAuthorityServices = List.copyOf(requiredAuthorityServices);
      reasons = List.copyOf(reasons);
    }
  }

  private final Map<String, MutableEntry> entries = new LinkedHashMap<>();

  public Entry register(
      String claimId,
      String sourceAttemptId,
      String sourceDeltaId,
      List<ProofControlModels.DependencyRef> dependencies,
      boolean attemptIncomplete) {
    return register(
        claimId,
        sourceAttemptId,
        sourceDeltaId,
        dependencies,
        AttemptArtifactKind.LOCAL_LEMMA,
        attemptIncomplete ? AttemptStatus.PARTIAL : AttemptStatus.COMPLETE,
        "unknown");
  }

  public Entry register(
      String claimId,
      String sourceAttemptId,
      String sourceDeltaId,
      List<ProofControlModels.DependencyRef> dependencies,
      AttemptArtifactKind claimKind,
      AttemptStatus sourceAttemptStatus,
      String sourceRouteStatus) {
    entries.computeIfAbsent(
        ProofControlModels.required(claimId, "claimId"),
        ignored ->
            new MutableEntry(
                claimId,
                ProofControlModels.required(sourceAttemptId, "sourceAttemptId"),
                ProofControlModels.blankToNull(sourceDeltaId),
                dependencies,
                claimKind,
                sourceAttemptStatus,
                sourceRouteStatus));
    return entries.get(claimId).snapshot();
  }

  public Entry recordLocalVerification(String claimId, String reportId) {
    MutableEntry entry = entry(claimId);
    entry.add(entry.localReports, reportId);
    entry.advance(State.LOCALLY_VERIFIED, "local verification " + reportId);
    return entry.snapshot();
  }

  public Entry recordIndependentVerification(
      String claimId, String reviewerAgentId, String authorAgentId, String reportId) {
    if (reviewerAgentId == null
        || reviewerAgentId.isBlank()
        || reviewerAgentId.equals(authorAgentId)) {
      throw new IllegalArgumentException("independent reviewer must differ from author");
    }
    MutableEntry entry = entry(claimId);
    entry.add(entry.independentReports, reportId);
    entry.advance(State.INDEPENDENTLY_VERIFIED, "independent verification " + reportId);
    return entry.snapshot();
  }

  public Entry recordRefereeAcceptance(
      String claimId,
      String refereeAgentId,
      String authorAgentId,
      String reviewId,
      boolean dependenciesValid,
      boolean scopeValid,
      boolean quantifiersValid,
      boolean evidenceTypeValid) {
    if (refereeAgentId == null
        || refereeAgentId.isBlank()
        || refereeAgentId.equals(authorAgentId)) {
      throw new IllegalArgumentException("referee must differ from claim author");
    }
    MutableEntry entry = entry(claimId);
    if (!(dependenciesValid && scopeValid && quantifiersValid && evidenceTypeValid)) {
      entry.reject("referee rejected one or more authority dimensions", reviewId);
      return entry.snapshot();
    }
    entry.add(entry.refereeReviews, reviewId);
    entry.advance(State.REFEREE_ACCEPTED, "referee acceptance " + reviewId);
    return entry.snapshot();
  }

  public PromotionProposal proposePromotion(
      String claimId,
      DependencyResolver.Resolution resolution,
      boolean scopeEligible,
      List<ProofControlModels.InferenceRisk> risks) {
    MutableEntry entry = entry(claimId);
    List<String> reasons = new ArrayList<>();
    if (entry.state.ordinal() < State.REFEREE_ACCEPTED.ordinal()) {
      reasons.add("claim lacks independent referee acceptance");
    }
    if (!resolution.resolved()) {
      reasons.add("claim dependencies are unresolved or invalid");
    }
    if (!scopeEligible) {
      reasons.add("claim scope is not Fact-eligible");
    }
    if (risks.stream().anyMatch(ProofControlModels.InferenceRisk::blocksFactPromotion)) {
      reasons.add("open inference risk blocks promotion");
    }
    if (entry.kind == AttemptArtifactKind.ROUTE_THEOREM
        && (entry.sourceAttemptStatus != AttemptStatus.COMPLETE
            || !"verified".equals(entry.sourceRouteStatus))) {
      reasons.add("route theorem requires a complete attempt and verified route");
    }
    boolean eligible = reasons.isEmpty();
    if (eligible) {
      entry.advance(State.FACT_CANDIDATE, "promotion proposal passed advisory gates");
    }
    String id =
        "fact_promotion_proposal_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "claim", claimId,
                        "eligible", eligible,
                        "reasons", reasons,
                        "state", entry.state.name()))
                .substring(0, 20);
    return new PromotionProposal(
        id,
        claimId,
        eligible,
        false,
        List.of("typed_memory_fact_gate", "proof_graph_authority"),
        reasons);
  }

  public Entry observeExternalAdmission(String claimId, String authorityEvidenceId) {
    MutableEntry entry = entry(claimId);
    if (entry.state == State.EXTERNALLY_ADMITTED_FACT) {
      entry.history.add(
          "external authority reaffirmed "
              + ProofControlModels.required(authorityEvidenceId, "authorityEvidenceId"));
      return entry.snapshot();
    }
    if (entry.state != State.FACT_CANDIDATE) {
      throw new IllegalStateException(
          "external Fact admission may only be observed from fact-candidate state");
    }
    entry.advance(
        State.EXTERNALLY_ADMITTED_FACT,
        "external authority evidence " + ProofControlModels.required(
            authorityEvidenceId, "authorityEvidenceId"));
    return entry.snapshot();
  }

  public Entry invalidate(String claimId, String reason, String evidenceId) {
    MutableEntry entry = entry(claimId);
    entry.invalidationReason = ProofControlModels.required(reason, "reason");
    entry.add(entry.invalidatingEvidence, evidenceId);
    entry.state = State.INVALIDATED;
    entry.history.add("invalidated:" + entry.invalidationReason);
    return entry.snapshot();
  }

  public List<Entry> invalidateDependents(
      String dependencyId, String reason, String evidenceId) {
    return entries.values().stream()
        .filter(
            value ->
                value.dependencies.stream()
                    .anyMatch(dependency -> dependency.targetId().equals(dependencyId)))
        .map(value -> invalidate(value.claimId, reason, evidenceId))
        .sorted(Comparator.comparing(Entry::claimId))
        .toList();
  }

  public Entry get(String claimId) {
    return entry(claimId).snapshot();
  }

  public List<Entry> entries() {
    return entries.values().stream()
        .map(MutableEntry::snapshot)
        .sorted(Comparator.comparing(Entry::claimId))
        .toList();
  }

  public ClaimLifecycleSnapshot snapshot() {
    Map<String, Entry> snapshot = new LinkedHashMap<>();
    entries.forEach((claimId, entry) -> snapshot.put(claimId, entry.snapshot()));
    return new ClaimLifecycleSnapshot(snapshot);
  }

  public void load(ClaimLifecycleSnapshot snapshot) {
    entries.clear();
    ClaimLifecycleSnapshot source =
        snapshot == null ? ClaimLifecycleSnapshot.empty() : snapshot;
    source.entries().forEach(
        (claimId, entry) -> {
          if (!claimId.equals(entry.claimId())) {
            throw new IllegalArgumentException("claim lifecycle snapshot key mismatch");
          }
          entries.put(claimId, new MutableEntry(entry));
        });
  }

  private MutableEntry entry(String claimId) {
    MutableEntry value = entries.get(claimId);
    if (value == null) {
      throw new IllegalArgumentException("unknown claim: " + claimId);
    }
    return value;
  }

  private static final class MutableEntry {
    private final String claimId;
    private final String sourceAttemptId;
    private final String sourceDeltaId;
    private State state = State.PROPOSED;
    private final List<ProofControlModels.DependencyRef> dependencies;
    private final List<String> localReports = new ArrayList<>();
    private final List<String> independentReports = new ArrayList<>();
    private final List<String> refereeReviews = new ArrayList<>();
    private final boolean attemptIncomplete;
    private final AttemptArtifactKind kind;
    private final AttemptStatus sourceAttemptStatus;
    private final String sourceRouteStatus;
    private String invalidationReason;
    private final List<String> invalidatingEvidence = new ArrayList<>();
    private final List<String> history = new ArrayList<>(List.of("registered:proposed"));

    private MutableEntry(
        String claimId,
        String sourceAttemptId,
        String sourceDeltaId,
        List<ProofControlModels.DependencyRef> dependencies,
        AttemptArtifactKind kind,
        AttemptStatus sourceAttemptStatus,
        String sourceRouteStatus) {
      this.claimId = claimId;
      this.sourceAttemptId = sourceAttemptId;
      this.sourceDeltaId = sourceDeltaId;
      this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
      this.kind = java.util.Objects.requireNonNull(kind, "claimKind");
      this.sourceAttemptStatus =
          java.util.Objects.requireNonNull(sourceAttemptStatus, "sourceAttemptStatus");
      this.sourceRouteStatus =
          ProofControlModels.required(sourceRouteStatus, "sourceRouteStatus");
      this.attemptIncomplete = sourceAttemptStatus != AttemptStatus.COMPLETE;
    }

    private MutableEntry(Entry entry) {
      this.claimId = entry.claimId();
      this.sourceAttemptId = entry.sourceAttemptId();
      this.sourceDeltaId = entry.sourceDeltaId();
      this.state = entry.state();
      this.dependencies = entry.dependencies();
      this.localReports.addAll(entry.localReportIds());
      this.independentReports.addAll(entry.independentReportIds());
      this.refereeReviews.addAll(entry.refereeReviewIds());
      this.attemptIncomplete = entry.sourceAttemptIncomplete();
      this.kind = entry.claimKind();
      this.sourceAttemptStatus = entry.sourceAttemptStatus();
      this.sourceRouteStatus = entry.sourceRouteStatus();
      this.invalidationReason = entry.invalidationReason();
      this.invalidatingEvidence.addAll(entry.invalidatingEvidenceIds());
      this.history.clear();
      this.history.addAll(entry.history());
    }

    private void advance(State next, String event) {
      if (state == State.INVALIDATED || state == State.REJECTED) {
        throw new IllegalStateException("terminal claim authority cannot advance");
      }
      if (next.ordinal() < state.ordinal()) {
        history.add(
            "late "
                + event
                + ":authority_preserved_"
                + state.name().toLowerCase(java.util.Locale.ROOT));
        return;
      }
      if (next.ordinal() > state.ordinal()) {
        state = next;
      }
      history.add(event + ":" + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void reject(String reason, String evidenceId) {
      state = State.REJECTED;
      invalidationReason = reason;
      add(invalidatingEvidence, evidenceId);
      history.add("rejected:" + reason);
    }

    private void add(List<String> target, String value) {
      String required = ProofControlModels.required(value, "evidence identifier");
      if (!target.contains(required)) {
        target.add(required);
      }
    }

    private Entry snapshot() {
      return new Entry(
          claimId,
          sourceAttemptId,
          sourceDeltaId,
          state,
          dependencies,
          localReports,
          independentReports,
          refereeReviews,
          attemptIncomplete,
          kind,
          sourceAttemptStatus,
          sourceRouteStatus,
          invalidationReason,
          invalidatingEvidence,
          history);
    }
  }
}
