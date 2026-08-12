package io.github.aililuola.mathproofmesh.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressFBWarnings(
    value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
    justification =
        "The public monitor is the intentional single lock for each mutable in-memory projection.")
public final class LemmaMemory {
  private final Map<String, ClaimCard> claims = new LinkedHashMap<>();
  private final Map<String, String> hashToId = new LinkedHashMap<>();
  private final Map<String, String> aliases = new LinkedHashMap<>();
  private final Set<String> committedStepIds = new LinkedHashSet<>();
  private final List<MemoryAuditEvent> audit = new ArrayList<>();
  private final Map<String, Long> versions = new LinkedHashMap<>();

  public synchronized List<ClaimCard> addMany(Collection<ClaimCard> candidates) {
    List<ClaimCard> added = new ArrayList<>();
    for (ClaimCard candidate : candidates) {
      String existingId = hashToId.get(candidate.contentHash());
      if (existingId != null) {
        if (!candidate.claimId().equals(existingId)) {
          aliases.put(candidate.claimId(), existingId);
        }
        ClaimCard existing = claims.get(existingId);
        if (candidate.status() == ClaimStatus.VERIFIED
            && candidate.verificationConfidence() != null
            && (existing.status() == ClaimStatus.PROPOSED
                || existing.status() == ClaimStatus.UNCERTAIN)) {
          ClaimCard upgraded =
              copy(
                  existing,
                  ClaimStatus.VERIFIED,
                  Math.max(
                      existing.verificationConfidence() == null
                          ? 0.0
                          : existing.verificationConfidence(),
                      candidate.verificationConfidence()),
                  union(existing.scopeLimitations(), candidate.scopeLimitations()));
          replace(upgraded, "claim_status_upgraded_by_evidence");
        }
        continue;
      }
      claims.put(candidate.claimId(), candidate);
      hashToId.put(candidate.contentHash(), candidate.claimId());
      versions.put(candidate.claimId(), 0L);
      added.add(candidate);
      record("claim_added", candidate.claimId(), Map.of());
    }
    reconcileDependencies();
    return List.copyOf(added);
  }

  public synchronized ClaimCard applyClaimReport(VerificationReport report) {
    String claimId = resolveClaimId(report.targetId());
    ClaimCard claim = claims.get(claimId);
    if (claim == null) {
      return null;
    }
    ClaimStatus status =
        switch (report.verdict()) {
          case PASS -> ClaimStatus.VERIFIED;
          case FAIL -> ClaimStatus.REJECTED;
          case UNCERTAIN, SKIPPED -> ClaimStatus.UNCERTAIN;
        };
    Double confidence =
        status == ClaimStatus.VERIFIED ? report.confidence() : claim.verificationConfidence();
    ClaimCard updated = copy(claim, status, confidence, claim.scopeLimitations());
    replace(updated, "claim_report_applied");
    reconcileDependencies();
    return claims.get(claimId);
  }

  public synchronized List<ClaimCard> markAttemptVerified(
      String attemptId, VerificationReport report) {
    Set<String> explicitlyRejected =
        report.issues().stream()
            .filter(issue -> issue.severity() == Severity.ERROR || issue.severity() == Severity.CRITICAL)
            .map(VerificationIssue::claimId)
            .filter(java.util.Objects::nonNull)
            .map(this::resolveClaimId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<ClaimCard> changed = new ArrayList<>();
    for (ClaimCard claim : List.copyOf(claims.values())) {
      if (!attemptId.equals(claim.sourceAttemptId())) {
        continue;
      }
      ClaimCard updated = claim;
      if (explicitlyRejected.contains(claim.claimId())) {
        updated =
            copy(
                claim,
                ClaimStatus.REJECTED,
                claim.verificationConfidence(),
                claim.scopeLimitations());
      } else if (report.verdict() == VerificationVerdict.PASS
          && claim.status() != ClaimStatus.VERIFIED) {
        updated =
            copy(
                claim,
                ClaimStatus.VERIFIED,
                report.confidence(),
                claim.scopeLimitations());
      }
      if (updated != claim) {
        replace(updated, "attempt_claim_scope_reconciled");
        changed.add(updated);
      } else {
        changed.add(claim);
      }
    }
    reconcileDependencies();
    return changed.stream()
        .map(item -> claims.get(item.claimId()))
        .toList();
  }

  public synchronized void registerCommittedStepIds(Collection<String> stepIds) {
    if (committedStepIds.addAll(stepIds)) {
      reconcileDependencies();
      record(
          "committed_steps_registered",
          "lemma-memory",
          Map.of("count", Integer.toString(stepIds.size())));
    }
  }

  public synchronized void replaceCommittedStepIds(Collection<String> stepIds) {
    committedStepIds.clear();
    committedStepIds.addAll(stepIds);
    reconcileDependencies();
    record(
        "committed_steps_replaced",
        "lemma-memory",
        Map.of("count", Integer.toString(stepIds.size())));
  }

  public synchronized String resolveClaimId(String claimId) {
    String current = claimId;
    Set<String> seen = new HashSet<>();
    while (aliases.containsKey(current) && seen.add(current)) {
      current = aliases.get(current);
    }
    return current;
  }

  public synchronized List<ClaimCard> claims() {
    return List.copyOf(claims.values());
  }

  public synchronized List<ClaimCard> verified() {
    Set<String> reusable = reusableVerifiedIds();
    return claims.values().stream()
        .filter(item -> item.status() == ClaimStatus.VERIFIED)
        .filter(item -> reusable.contains(item.claimId()))
        .toList();
  }

  public synchronized List<ClaimCard> uncertain() {
    return claims.values().stream()
        .filter(
            item ->
                item.status() == ClaimStatus.PROPOSED
                    || item.status() == ClaimStatus.UNCERTAIN)
        .toList();
  }

  public synchronized List<ClaimCard> rejected() {
    return claims.values().stream()
        .filter(item -> item.status() == ClaimStatus.REJECTED)
        .toList();
  }

  public synchronized LemmaMemorySnapshot snapshot() {
    return new LemmaMemorySnapshot(
        claims, aliases, List.copyOf(committedStepIds), audit);
  }

  public static LemmaMemory restore(LemmaMemorySnapshot snapshot) {
    LemmaMemory memory = new LemmaMemory();
    synchronized (memory) {
      memory.claims.putAll(snapshot.claims());
      snapshot.claims().values().forEach(
          claim -> {
            memory.hashToId.put(claim.contentHash(), claim.claimId());
            memory.versions.put(claim.claimId(), 0L);
          });
      memory.aliases.putAll(snapshot.claimAliases());
      memory.committedStepIds.addAll(snapshot.committedStepIds());
      memory.audit.addAll(snapshot.audit());
      memory.reconcileDependencies();
    }
    return memory;
  }

  public synchronized List<MemoryAuditEvent> audit() {
    return List.copyOf(audit);
  }

  private void reconcileDependencies() {
    Set<String> verifiedIds = new LinkedHashSet<>();
    claims.values().stream()
        .filter(item -> item.status() == ClaimStatus.VERIFIED)
        .map(ClaimCard::claimId)
        .forEach(verifiedIds::add);

    Set<String> cycleNodes = cycleNodes(verifiedIds);
    for (String claimId : List.copyOf(verifiedIds)) {
      ClaimCard claim = claims.get(claimId);
      List<String> limitations = new ArrayList<>(claim.scopeLimitations());
      boolean invalid = false;
      for (String dependency : localClaimDependencies(claim)) {
        String resolved = resolveClaimId(dependency);
        ClaimCard target = claims.get(resolved);
        if (target == null) {
          addOnce(limitations, "missing dependency: " + dependency);
          invalid = true;
        } else if (target.status() != ClaimStatus.VERIFIED) {
          addOnce(limitations, "dependency is not verified: " + resolved);
          invalid = true;
        }
      }
      for (String stepId : referencedStepIds(claim)) {
        if (!localStepIds(claim).contains(stepId)
            && !committedStepIds.contains(stepId)) {
          addOnce(limitations, "missing local proof step: " + stepId);
          invalid = true;
        }
      }
      if (cycleNodes.contains(claimId)) {
        addOnce(limitations, "dependency cycle detected");
        invalid = true;
      }
      if (invalid) {
        replace(
            copy(
                claim,
                ClaimStatus.UNCERTAIN,
                claim.verificationConfidence(),
                limitations),
            "claim_dependency_invalidated");
      }
    }
  }

  private Set<String> reusableVerifiedIds() {
    Map<String, ClaimCard> verified = new LinkedHashMap<>();
    claims.values().stream()
        .filter(item -> item.status() == ClaimStatus.VERIFIED)
        .forEach(item -> verified.put(item.claimId(), item));
    Set<String> reusable = new LinkedHashSet<>();
    boolean changed;
    do {
      changed = false;
      for (ClaimCard claim : verified.values()) {
        if (reusable.contains(claim.claimId())) {
          continue;
        }
        boolean stepsValid =
            referencedStepIds(claim).stream()
                .allMatch(
                    step ->
                        localStepIds(claim).contains(step)
                            || committedStepIds.contains(step));
        boolean claimsValid =
            localClaimDependencies(claim).stream()
                .map(this::resolveClaimId)
                .allMatch(reusable::contains);
        if (stepsValid && claimsValid) {
          changed |= reusable.add(claim.claimId());
        }
      }
    } while (changed);
    return reusable;
  }

  private Set<String> cycleNodes(Set<String> verifiedIds) {
    Map<String, Integer> indegree = new LinkedHashMap<>();
    Map<String, List<String>> dependents = new HashMap<>();
    verifiedIds.forEach(id -> indegree.put(id, 0));
    for (String source : verifiedIds) {
      for (String rawTarget : localClaimDependencies(claims.get(source))) {
        String target = resolveClaimId(rawTarget);
        if (!verifiedIds.contains(target)) {
          continue;
        }
        dependents.computeIfAbsent(target, ignored -> new ArrayList<>()).add(source);
        indegree.compute(source, (key, value) -> value == null ? 1 : value + 1);
      }
    }
    Deque<String> queue = new ArrayDeque<>();
    indegree.forEach((key, value) -> {
      if (value == 0) {
        queue.add(key);
      }
    });
    Set<String> visited = new LinkedHashSet<>();
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      visited.add(current);
      for (String dependent : dependents.getOrDefault(current, List.of())) {
        int degree = indegree.computeIfPresent(dependent, (key, value) -> value - 1);
        if (degree == 0) {
          queue.add(dependent);
        }
      }
    }
    Set<String> result = new LinkedHashSet<>(verifiedIds);
    result.removeAll(visited);
    return result;
  }

  private static List<String> localClaimDependencies(ClaimCard claim) {
    List<String> result = new ArrayList<>();
    for (String dependency : claim.dependencies()) {
      if (dependency.startsWith("claim:")) {
        result.add(dependency.substring("claim:".length()));
      } else if (!dependency.startsWith("step:")
          && !localStepIds(claim).contains(dependency)) {
        result.add(dependency);
      }
    }
    claim.dependencyRefs().stream()
        .filter(node -> "local_claim".equals(node.path("kind").asText()))
        .map(node -> node.path("target_id").asText())
        .filter(value -> !value.isBlank())
        .forEach(result::add);
    return List.copyOf(new LinkedHashSet<>(result));
  }

  private static Set<String> referencedStepIds(ClaimCard claim) {
    Set<String> result = new LinkedHashSet<>();
    claim.dependencies().stream()
        .filter(value -> value.startsWith("step:"))
        .map(value -> value.substring("step:".length()))
        .forEach(result::add);
    claim.dependencyRefs().stream()
        .filter(node -> "local_step".equals(node.path("kind").asText()))
        .map(node -> node.path("target_id").asText())
        .filter(value -> !value.isBlank())
        .forEach(result::add);
    return result;
  }

  private static Set<String> localStepIds(ClaimCard claim) {
    return claim.proofSteps().stream()
        .map(io.github.aililuola.mathproofmesh.contract.ProofStep::stepId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private void replace(ClaimCard replacement, String eventType) {
    claims.put(replacement.claimId(), replacement);
    versions.compute(replacement.claimId(), (key, value) -> value == null ? 0L : value + 1L);
    record(eventType, replacement.claimId(), Map.of("status", replacement.status().value()));
  }

  private void record(String eventType, String itemId, Map<String, String> details) {
    audit.add(
        new MemoryAuditEvent(
            audit.size() + 1L,
            eventType,
            itemId,
            versions.getOrDefault(itemId, 0L),
            details));
  }

  private static ClaimCard copy(
      ClaimCard source,
      ClaimStatus status,
      Double verificationConfidence,
      List<String> scopeLimitations) {
    return new ClaimCard(
        source.assumptions(),
        source.claimId(),
        source.conclusion(),
        source.contentHash(),
        source.counterexampleRisk(),
        source.dependencies(),
        source.dependencyRefs(),
        source.evidenceRefs(),
        source.proofSteps(),
        scopeLimitations,
        source.selfConfidence(),
        source.sourceAgentId(),
        source.sourceAttemptId(),
        source.sourceDeltaId(),
        source.statement(),
        status,
        source.tags(),
        verificationConfidence);
  }

  private static List<String> union(List<String> left, List<String> right) {
    Set<String> values = new LinkedHashSet<>(left);
    values.addAll(right);
    return List.copyOf(values);
  }

  private static void addOnce(List<String> values, String value) {
    if (!values.contains(value)) {
      values.add(value);
    }
  }
}
