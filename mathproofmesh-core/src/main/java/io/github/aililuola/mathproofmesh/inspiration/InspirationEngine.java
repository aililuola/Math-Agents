package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationCallReservation;
import io.github.aililuola.mathproofmesh.contract.InspirationMaterialization;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationProposalAssignment;
import io.github.aililuola.mathproofmesh.contract.InspirationReview;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded orchestration for proposal generation, independent review, and
 * materialization. It has no Fact or checkpoint authority.
 */
public final class InspirationEngine {
  private final InspirationPolicy policy;
  private final InspirationMechanismRegistry mechanisms;
  private final InspirationReferee referee;
  private final MechanismContextProfile contexts;
  private final Map<String, ExecutionResult> completed = new LinkedHashMap<>();
  private final Map<String, InspirationProposal> retainedProposals = new LinkedHashMap<>();
  private final Map<String, InspirationReview> retainedReviews = new LinkedHashMap<>();
  private final Map<String, Integer> materializedByTrigger = new LinkedHashMap<>();
  private final Map<String, Integer> newRoutesByTrigger = new LinkedHashMap<>();
  private final Map<String, Integer> reviewedByTask = new LinkedHashMap<>();
  private final Set<String> admittedSignatureHashes = new LinkedHashSet<>();
  private final List<AuditEvent> audit = new ArrayList<>();
  private final Map<String, InspirationCallReservation> reservations = new LinkedHashMap<>();

  public InspirationEngine(
      InspirationPolicy policy,
      InspirationMechanismRegistry mechanisms,
      InspirationReferee referee,
      MechanismContextProfile contexts) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.mechanisms = java.util.Objects.requireNonNull(mechanisms, "mechanisms");
    this.referee = java.util.Objects.requireNonNull(referee, "referee");
    this.contexts = java.util.Objects.requireNonNull(contexts, "contexts");
  }

  public synchronized InspirationCallReservation reserveCycle(
      InspirationTask task,
      InspirationAssignmentPlan plan,
      InspirationSnapshot snapshot,
      int skepticCallsPerProposal,
      int routeAttemptCalls) {
    int assignments = plan.assignments().size();
    int proposerCalls = assignments;
    int refereeCalls = policy.requireIndependentReferee() ? assignments : 0;
    int skepticCalls = Math.max(0, skepticCallsPerProposal) * assignments;
    int routeCalls = Math.max(0, routeAttemptCalls);
    int total = proposerCalls + refereeCalls + skepticCalls + routeCalls;
    String id =
        "inspiration_budget_"
            + CanonicalJson.stableHash(
                    List.of(task.triggerId(), task.taskId(), plan.roundIndex(), total))
                .substring(0, 16);
    InspirationCallReservation existing = reservations.get(id);
    if (existing != null) {
      return existing;
    }
    if (policy.mode() != InspirationPolicy.Mode.ACTIVE) {
      InspirationCallReservation recorded =
          new InspirationCallReservation(
              0,
              0,
              Map.of(),
              proposerCalls,
              refereeCalls,
              total,
              id,
              total,
              plan.roundIndex(),
              routeCalls,
              skepticCalls,
              "interrupted",
              task.taskId(),
              task.triggerId());
      reservations.put(id, recorded);
      audit.add(
          new AuditEvent(
              "reservation_recorded_without_mutation",
              task.taskId(),
              "off/shadow mode did not reserve calls"));
      return recorded;
    }
    if (total > snapshot.schedulableCalls()) {
      throw new IllegalStateException("complete inspiration cycle exceeds protected call budget");
    }
    InspirationCallReservation reservation =
        new InspirationCallReservation(
            0,
            0,
            Map.of(),
            proposerCalls,
            refereeCalls,
            0,
            id,
            total,
            plan.roundIndex(),
            routeCalls,
            skepticCalls,
            "active",
            task.taskId(),
            task.triggerId());
    reservations.put(id, reservation);
    return reservation;
  }

  public synchronized InspirationCallReservation reconcileReservation(
      String reservationId, Map<String, Integer> chargedPhaseCalls, boolean interrupted) {
    InspirationCallReservation value = reservations.get(reservationId);
    if (value == null) {
      throw new IllegalArgumentException("unknown inspiration reservation: " + reservationId);
    }
    Map<String, Integer> phases =
        chargedPhaseCalls == null ? Map.of() : Map.copyOf(chargedPhaseCalls);
    int charged =
        phases.values().stream().mapToInt(item -> Math.max(0, item)).sum();
    int consumed = Math.min(value.reservedCalls(), charged);
    int overrun = Math.max(0, charged - value.reservedCalls());
    int released = Math.max(0, value.reservedCalls() - consumed);
    InspirationCallReservation reconciled =
        new InspirationCallReservation(
            consumed,
            overrun,
            phases,
            value.proposerCalls(),
            value.refereeCalls(),
            released,
            value.reservationId(),
            value.reservedCalls(),
            value.roundIndex(),
            value.routeAttemptCalls(),
            value.skepticCalls(),
            interrupted ? "interrupted" : "completed",
            value.taskId(),
            value.triggerId());
    reservations.put(reservationId, reconciled);
    return reconciled;
  }

  public synchronized ExecutionResult execute(
      InspirationTask task,
      InspirationProposalAssignment assignment,
      InspirationSnapshot snapshot,
      List<NoveltySignature> existingSignatures,
      Set<String> openObligationIds,
      String reviewerAgentId,
      List<String> verifiedFacts,
      List<String> negativeAnalogies,
      ProposalProvider provider) {
    if (!policy.runs()) {
      return recordOnly(
          task,
          "off",
          "inspiration mode is off",
          0);
    }
    if (!mechanisms.isSchedulable(task.mechanism())) {
      return recordOnly(task, "rejected", "mechanism is disabled or unschedulable", 0);
    }
    if (policy.recordsOnly()) {
      return recordOnly(
          task,
          "shadow_only",
          "shadow mode records the task without provider, budget, graph, or memory mutation",
          0);
    }
    Admission admission = admit(task, snapshot, 1);
    if (!admission.accepted()) {
      return recordOnly(task, "rejected", admission.reason(), 0);
    }
    MechanismContextProfile.PromptContext context =
        contexts.build(
            task.mechanism(),
            assignment.contextMode(),
            "problem_hash=" + snapshot.problemHash(),
            task.targetObligationIds(),
            verifiedFacts,
            negativeAnalogies,
            Map.of(
                "round", snapshot.roundIndex(),
                "remaining_calls", snapshot.remainingCalls()),
            List.of());
    InspirationProposal proposal = provider.generate(context, assignment);
    retainedProposals.putIfAbsent(proposal.proposalId(), proposal);
    ExecutionResult prior = completed.get(proposal.proposalId());
    if (prior != null) {
      return prior;
    }
    if (!proposal.taskId().equals(task.taskId())
        || proposal.mechanism() != task.mechanism()
        || !proposal.sourceAgentId().equals(assignment.proposerAgentId())) {
      return finish(
          proposal,
          null,
          materialization(proposal.proposalId(), "rejected", "proposal assignment mismatch"),
          false,
          1,
          "proposal assignment mismatch");
    }
    List<NoveltySignature> comparison = new ArrayList<>();
    if (existingSignatures != null) {
      comparison.addAll(existingSignatures);
    }
    if (admittedSignatureHashes.contains(proposal.noveltySignature().normalizedHash())
        || retainedProposals.values().stream()
            .filter(item -> !item.proposalId().equals(proposal.proposalId()))
            .anyMatch(
                item ->
                    item.noveltySignature()
                        .normalizedHash()
                        .equals(proposal.noveltySignature().normalizedHash()))) {
      return finish(
          proposal,
          null,
          materialization(proposal.proposalId(), "rejected", "duplicate proposal"),
          false,
          1,
          "candidate deduplicated before review");
    }
    int reviewed = reviewedByTask.getOrDefault(task.taskId(), 0);
    if (reviewed >= policy.limits().maxReviewedPerTask()) {
      return finish(
          proposal,
          null,
          materialization(
              proposal.proposalId(), "rejected", "review cap exhausted; proposal deferred"),
          false,
          1,
          "review deferred");
    }
    if (reviewerAgentId == null || reviewerAgentId.isBlank()) {
      return finish(
          proposal,
          null,
          materialization(
              proposal.proposalId(), "rejected", "independent reviewer unavailable"),
          false,
          1,
          "review deferred");
    }
    InspirationReview review =
        referee.review(
            proposal,
            reviewerAgentId,
            openObligationIds,
            comparison,
            List.of(),
            List.of());
    retainedReviews.put(proposal.proposalId(), review);
    reviewedByTask.merge(task.taskId(), 1, Integer::sum);
    if (review.recommendation().equals("reject")) {
      return finish(
          proposal,
          review,
          materialization(
              proposal.proposalId(), "rejected", "independent referee rejected proposal"),
          false,
          1,
          "referee rejection retained for finalization");
    }
    int triggerCount = materializedByTrigger.getOrDefault(task.triggerId(), 0);
    if (triggerCount >= policy.limits().maxMaterializedPerTrigger()) {
      return finish(
          proposal,
          review,
          materialization(proposal.proposalId(), "rejected", "per-trigger cap reached"),
          false,
          1,
          "cap enforced across mechanisms");
    }
    String action = action(review);
    if (action.equals("route_created")) {
      int routes = newRoutesByTrigger.getOrDefault(task.triggerId(), 0);
      if (routes >= policy.limits().maxNewRoutesPerTrigger()
          || !snapshot.pathCapacityAvailable()) {
        return finish(
            proposal,
            review,
            materialization(proposal.proposalId(), "rejected", "route or path cap reached"),
            false,
            1,
            "route cap enforced");
      }
      newRoutesByTrigger.merge(task.triggerId(), 1, Integer::sum);
    }
    materializedByTrigger.merge(task.triggerId(), 1, Integer::sum);
    admittedSignatureHashes.add(proposal.noveltySignature().normalizedHash());
    InspirationMaterialization materialization =
        new InspirationMaterialization(
            action,
            List.of(),
            proposal.generatedObligations(),
            proposal.proposalId(),
            "independently reviewed inspiration proposal",
            action.equals("attached")
                ? proposal.targetRouteIds().stream().findFirst().orElse(null)
                : action.equals("route_created")
                    ? "inspired_" + proposal.proposalId()
                    : null);
    return finish(
        proposal, review, materialization, true, 1, "active proposal passed all gates");
  }

  public synchronized ExecutionResult reassignDeferred(
      InspirationProposal proposal,
      InspirationSnapshot snapshot,
      Set<String> openObligationIds,
      List<NoveltySignature> signatures,
      String newReviewerId) {
    if (completed.containsKey(proposal.proposalId())
        && completed.get(proposal.proposalId()).review() != null) {
      return completed.get(proposal.proposalId());
    }
    InspirationReview review =
        referee.review(
            proposal, newReviewerId, openObligationIds, signatures, List.of(), List.of());
    retainedReviews.put(proposal.proposalId(), review);
    InspirationMaterialization materialization =
        review.recommendation().equals("reject")
            ? materialization(proposal.proposalId(), "rejected", "reassigned review rejected")
            : materialization(
                proposal.proposalId(),
                "stored_insight",
                "review completed; later scheduler materialization required");
    completed.remove(proposal.proposalId());
    return finish(proposal, review, materialization, false, 0, "deferred review reassigned");
  }

  public synchronized List<Map<String, Object>> rejectedBlindPackets() {
    return completed.values().stream()
        .filter(item -> item.materialization().action().equals("rejected"))
        .map(
            item ->
                referee.negativeBlindPacket(
                    item.proposal(), item.materialization().reason()))
        .toList();
  }

  public synchronized List<AuditEvent> audit() {
    return List.copyOf(audit);
  }

  public synchronized int retainedProposalCount() {
    return retainedProposals.size();
  }

  private Admission admit(InspirationTask task, InspirationSnapshot snapshot, int plannedCalls) {
    if (plannedCalls > snapshot.schedulableCalls()) {
      return new Admission(false, "scheduler admission rejected protected-budget call");
    }
    if (task.maxProposals() > policy.limits().maxProposalsPerTask()) {
      return new Admission(false, "task exceeds proposal cap");
    }
    return new Admission(true, "scheduler admission passed");
  }

  private ExecutionResult recordOnly(
      InspirationTask task, String action, String reason, int providerCalls) {
    audit.add(new AuditEvent(action, task.taskId(), reason));
    return new ExecutionResult(
        null,
        null,
        new InspirationMaterialization(
            action.equals("off") ? "rejected" : action,
            List.of(),
            List.of(),
            "task_" + task.taskId(),
            reason,
            null),
        false,
        false,
        false,
        providerCalls,
        reason);
  }

  private ExecutionResult finish(
      InspirationProposal proposal,
      InspirationReview review,
      InspirationMaterialization materialization,
      boolean businessMutation,
      int providerCalls,
      String reason) {
    ExecutionResult result =
        new ExecutionResult(
            proposal,
            review,
            materialization,
            businessMutation,
            false,
            false,
            providerCalls,
            reason);
    completed.putIfAbsent(proposal.proposalId(), result);
    audit.add(new AuditEvent(materialization.action(), proposal.proposalId(), reason));
    return completed.get(proposal.proposalId());
  }

  private static InspirationMaterialization materialization(
      String proposalId, String action, String reason) {
    return new InspirationMaterialization(
        action, List.of(), List.of(), proposalId, reason, null);
  }

  private static String action(InspirationReview review) {
    return switch (review.recommendation()) {
      case "store_insight" -> "stored_insight";
      case "attach_to_existing_route" -> "attached";
      case "create_new_route" -> "route_created";
      case "request_computation" -> "computation_requested";
      case "request_bridge_verification" -> "bridge_requested";
      default -> "rejected";
    };
  }

  @FunctionalInterface
  public interface ProposalProvider {
    InspirationProposal generate(
        MechanismContextProfile.PromptContext context,
        InspirationProposalAssignment assignment);
  }

  private record Admission(boolean accepted, String reason) {}

  public record AuditEvent(String action, String subjectId, String reason) {}

  public record ExecutionResult(
      InspirationProposal proposal,
      InspirationReview review,
      InspirationMaterialization materialization,
      boolean businessMutation,
      boolean writesFact,
      boolean closesCheckpoint,
      int providerCalls,
      String reason) {}
}
