package io.github.aililuola.mathproofmesh.persistence;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.memory.MemoryEnvelopeTransitions;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphPolicy;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcMemoryProofGraphRepository {
  private final String runId;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final EventLogRepository events;

  public JdbcMemoryProofGraphRepository(
      String runId, JdbcClient jdbc, TransactionTemplate transactions) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId is required");
    }
    this.runId = runId;
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
    events = new EventLogRepository(jdbc);
  }

  public void saveMemory(MessageEnvelope message) {
    transactions.executeWithoutResult(ignored -> saveMemoryInternal(message));
  }

  public void saveObligation(
      ProofObligation obligation, long version, boolean needsReverify) {
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
    int inserted =
        jdbc.sql(
                """
                INSERT INTO proof_obligation (
                  run_id, obligation_id, obligation_type, status, priority,
                  debt, owner_route_id, payload, version, needs_reverify
                ) VALUES (
                  :runId, :obligationId, :obligationType, :status, :priority,
                  0, :ownerRouteId, CAST(:payload AS jsonb), :version,
                  :needsReverify
                )
                ON CONFLICT (run_id, obligation_id) DO NOTHING
                """)
            .param("runId", runId)
            .param("obligationId", obligation.obligationId())
            .param("obligationType", obligation.kind().value())
            .param("status", obligation.status())
            .param("priority", (int) Math.round(obligation.priority() * 1000))
            .param(
                "ownerRouteId",
                obligation.routeIds().isEmpty()
                    ? null
                    : obligation.routeIds().getFirst())
            .param("payload", ContractObjectMapper.write(obligation))
            .param("version", version)
            .param("needsReverify", needsReverify)
            .update();
    if (inserted == 0) {
      String payload =
          jdbc.sql(
                  """
                  SELECT payload::text
                  FROM proof_obligation
                  WHERE run_id = :runId AND obligation_id = :obligationId
                  """)
              .param("runId", runId)
              .param("obligationId", obligation.obligationId())
              .query(String.class)
              .single();
      ProofObligation existing =
          ContractObjectMapper.read(payload, ProofObligation.class);
      if (!existing.contentHash().equals(obligation.contentHash())) {
        throw new OptimisticLockException(obligation.obligationId(), version);
      }
    }
  }

  public void saveEdge(ProofGraphEdge edge) {
    Set<String> obligationIds =
        Set.copyOf(
            jdbc.sql(
                    """
                    SELECT obligation_id
                    FROM proof_obligation
                    WHERE run_id = :runId
                      AND obligation_id IN (:nodeIds)
                    """)
                .param("runId", runId)
                .param("nodeIds", Set.of(edge.sourceId(), edge.targetId()))
                .query(String.class)
                .list());
    jdbc.sql(
            """
            INSERT INTO proof_graph_edge (
              run_id, edge_id, source_ref, source_type, target_ref,
              target_type, relation, status, provenance_ref, payload
            ) VALUES (
              :runId, :edgeId, :sourceRef, :sourceType, :targetRef,
              :targetType, :relation, 'active', :provenanceRef,
              CAST(:payload AS jsonb)
            )
            ON CONFLICT (run_id, edge_id) DO NOTHING
            """)
        .param("runId", runId)
        .param("edgeId", edge.edgeId())
        .param("sourceRef", edge.sourceId())
        .param(
            "sourceType",
            obligationIds.contains(edge.sourceId()) ? "obligation" : "memory")
        .param("targetRef", edge.targetId())
        .param(
            "targetType",
            obligationIds.contains(edge.targetId()) ? "obligation" : "memory")
        .param("relation", edge.edgeType().value())
        .param("provenanceRef", edge.evidenceMessageId())
        .param("payload", ContractObjectMapper.write(edge))
        .update();
  }

  public void saveGraphState(
      String problemHash, boolean frozen, long graphVersion) {
    jdbc.sql(
            """
            INSERT INTO proof_graph_state (
              run_id, problem_hash, frozen, graph_version
            ) VALUES (
              :runId, :problemHash, :frozen, :graphVersion
            )
            ON CONFLICT (run_id) DO UPDATE
            SET problem_hash = EXCLUDED.problem_hash,
                frozen = EXCLUDED.frozen,
                graph_version = EXCLUDED.graph_version,
                updated_at = clock_timestamp()
            """)
        .param("runId", runId)
        .param("problemHash", problemHash)
        .param("frozen", frozen)
        .param("graphVersion", graphVersion)
        .update();
  }

  public LoadedProofGraph loadProofGraph() {
    int queryCount = 0;
    GraphState graphState =
        jdbc.sql(
                """
                SELECT problem_hash, frozen
                FROM proof_graph_state
                WHERE run_id = :runId
                """)
            .param("runId", runId)
            .query(
                (result, row) ->
                    new GraphState(
                        result.getString("problem_hash"),
                        result.getBoolean("frozen")))
            .single();
    queryCount++;

    Map<String, ProofObligation> obligations = new LinkedHashMap<>();
    Map<String, Long> versions = new LinkedHashMap<>();
    Set<String> needsReverify = new LinkedHashSet<>();
    jdbc.sql(
            """
            SELECT obligation_id, status, payload::text, version, needs_reverify
            FROM proof_obligation
            WHERE run_id = :runId
            ORDER BY obligation_id
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                {
                  boolean reverify = result.getBoolean("needs_reverify");
                  ProofObligation persisted =
                      ContractObjectMapper.read(
                          result.getString("payload"), ProofObligation.class);
                  return new ObligationRow(
                      result.getString("obligation_id"),
                      withStatus(
                          persisted,
                          result.getString("status"),
                          reverify),
                      result.getLong("version"),
                      reverify);
                })
        .list()
        .forEach(
            row -> {
              obligations.put(row.id(), row.obligation());
              versions.put(row.id(), row.version());
              if (row.needsReverify()) {
                needsReverify.add(row.id());
              }
            });
    queryCount++;

    Map<String, ProofGraphEdge> edges = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT edge_id, payload::text
            FROM proof_graph_edge
            WHERE run_id = :runId AND status = 'active'
            ORDER BY edge_id
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                Map.entry(
                    result.getString("edge_id"),
                    ContractObjectMapper.read(
                        result.getString("payload"), ProofGraphEdge.class)))
        .list()
        .forEach(entry -> edges.put(entry.getKey(), entry.getValue()));
    queryCount++;

    Map<String, MessageEnvelope> claims = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT memory_id, payload::text, version
            FROM memory_item
            WHERE run_id = :runId
            ORDER BY memory_id
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                new MemoryRow(
                    result.getString("memory_id"),
                    ContractObjectMapper.read(
                        result.getString("payload"), MessageEnvelope.class),
                    result.getLong("version")))
        .list()
        .forEach(
            row -> {
              claims.put(row.id(), row.message());
              versions.put(row.id(), row.version());
            });
    queryCount++;

    ProofGraphSnapshot snapshot =
        new ProofGraphSnapshot(
            graphState.problemHash(),
            graphState.frozen(),
            obligations,
            claims,
            edges,
            Map.of(),
            needsReverify,
            versions,
            List.of());
    return new LoadedProofGraph(
        ProofGraphStore.restore(snapshot, ProofGraphPolicy.defaults()), queryCount);
  }

  public MemoryInvalidationBatch invalidateByCounterexample(
      MessageEnvelope counterexample, Collection<String> directMemoryIds) {
    return invalidateByCounterexample(counterexample, directMemoryIds, () -> {});
  }

  public MemoryInvalidationBatch invalidateByCounterexample(
      MessageEnvelope counterexample,
      Collection<String> directMemoryIds,
      Runnable beforeCommitHook) {
    if (counterexample.evidenceType() != EvidenceType.COUNTEREXAMPLE
        || counterexample.memoryTier() != MemoryTier.NEGATIVE) {
      throw new IllegalArgumentException(
          "transactional invalidation requires a Negative counterexample");
    }
    Set<String> direct = Set.copyOf(directMemoryIds);
    if (direct.isEmpty()) {
      throw new IllegalArgumentException("at least one invalidated memory item is required");
    }
    String batchId =
        CanonicalJson.stableHash(
            List.of(runId, counterexample.messageId(), "counterexample-invalidation"));
    return java.util.Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              lockRun();
              List<String> replayed =
                  jdbc.sql(
                          """
                          SELECT memory_id
                          FROM memory_invalidation
                          WHERE run_id = :runId
                            AND propagation_batch_id = :batchId
                          ORDER BY memory_id
                          """)
                      .param("runId", runId)
                      .param("batchId", batchId)
                      .query(String.class)
                      .list();
              if (!replayed.isEmpty()) {
                return new MemoryInvalidationBatch(
                    batchId,
                    counterexample.messageId(),
                    replayed,
                    reopenedForBatch(batchId),
                    true);
              }

              saveMemoryInternal(counterexample);
              List<MemoryRow> affected =
                  jdbc.sql(
                          """
                          WITH RECURSIVE affected(memory_id) AS (
                            SELECT memory_id
                            FROM memory_item
                            WHERE run_id = :runId AND memory_id IN (:directIds)
                            UNION
                            SELECT dependency.source_memory_id
                            FROM memory_dependency AS dependency
                            JOIN affected
                              ON affected.memory_id = dependency.target_memory_id
                            JOIN memory_item AS dependent
                              ON dependent.run_id = dependency.run_id
                             AND dependent.memory_id = dependency.source_memory_id
                            WHERE dependency.run_id = :runId
                              AND dependent.memory_tier <> 'negative'
                              AND dependent.memory_id <> :counterexampleId
                          )
                          SELECT item.memory_id, item.payload::text, item.version
                          FROM memory_item AS item
                          JOIN affected ON affected.memory_id = item.memory_id
                          WHERE item.run_id = :runId
                          ORDER BY item.memory_id
                          FOR UPDATE
                          """)
                      .param("runId", runId)
                      .param("directIds", direct)
                      .param("counterexampleId", counterexample.messageId())
                      .query(
                          (result, row) ->
                              new MemoryRow(
                                  result.getString("memory_id"),
                                  ContractObjectMapper.read(
                                      result.getString("payload"),
                                      MessageEnvelope.class),
                                  result.getLong("version")))
                      .list();
              Set<String> found =
                  affected.stream()
                      .map(MemoryRow::id)
                      .collect(
                          java.util.stream.Collectors.toCollection(
                              LinkedHashSet::new));
              if (!found.containsAll(direct)) {
                Set<String> missing = new LinkedHashSet<>(direct);
                missing.removeAll(found);
                throw new IllegalArgumentException(
                    "unknown memory items: " + String.join(",", missing));
              }

              for (MemoryRow row : affected) {
                MessageEnvelope invalidated =
                    MemoryEnvelopeTransitions.toNegative(row.message());
                int updated =
                    jdbc.sql(
                            """
                            UPDATE memory_item
                            SET memory_tier = 'negative',
                                state = 'invalidated',
                                content_hash = :contentHash,
                                payload = CAST(:payload AS jsonb),
                                invalidated_reason = :reason,
                                propagation_batch_id = :batchId,
                                version = version + 1,
                                updated_at = clock_timestamp()
                            WHERE run_id = :runId
                              AND memory_id = :memoryId
                              AND version = :version
                            """)
                        .param("contentHash", invalidated.contentHash())
                        .param("payload", ContractObjectMapper.write(invalidated))
                        .param(
                            "reason",
                            "counterexample:" + counterexample.messageId())
                        .param("batchId", batchId)
                        .param("runId", runId)
                        .param("memoryId", row.id())
                        .param("version", row.version())
                        .update();
                if (updated != 1) {
                  throw new OptimisticLockException(row.id(), row.version());
                }
                String invalidationId =
                    CanonicalJson.stableHash(List.of(runId, batchId, row.id()));
                jdbc.sql(
                        """
                        INSERT INTO memory_invalidation (
                          run_id, invalidation_id, memory_id,
                          counterexample_claim_id, reason,
                          propagation_batch_id, payload
                        ) VALUES (
                          :runId, :invalidationId, :memoryId,
                          :counterexampleId, :reason, :batchId,
                          CAST(:payload AS jsonb)
                        )
                        """)
                    .param("runId", runId)
                    .param("invalidationId", invalidationId)
                    .param("memoryId", row.id())
                    .param("counterexampleId", counterexample.messageId())
                    .param(
                        "reason",
                        "counterexample:" + counterexample.messageId())
                    .param("batchId", batchId)
                    .param(
                        "payload",
                        ContractObjectMapper.write(
                            Map.of(
                                "memory_id", row.id(),
                                "counterexample_message_id",
                                    counterexample.messageId())))
                    .update();
              }
              List<String> affectedIds =
                  affected.stream().map(MemoryRow::id).toList();
              List<String> reopened = reopenAffectedObligations(affectedIds, batchId);
              String payload =
                  ContractObjectMapper.write(
                      Map.of(
                          "batch_id", batchId,
                          "counterexample_message_id",
                              counterexample.messageId(),
                          "invalidated_memory_ids", affectedIds,
                          "reopened_obligation_ids", reopened));
              DomainEvent event =
                  DomainEvent.create(
                      CanonicalJson.stableHash(List.of(runId, batchId, "event")),
                      runId,
                      "memory",
                      counterexample.messageId(),
                      affected.size(),
                      "memory.counterexample_propagated",
                      payload);
              events.appendWithOutbox(event);
              beforeCommitHook.run();
              return new MemoryInvalidationBatch(
                  batchId,
                  counterexample.messageId(),
                  affectedIds,
                  reopened,
                  false);
            }),
        "memory invalidation transaction result");
  }

  private void saveMemoryInternal(MessageEnvelope message) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO memory_item (
                  run_id, memory_id, message_id, memory_tier, state,
                  content_hash, payload
                ) VALUES (
                  :runId, :memoryId, :messageId, :memoryTier, :state,
                  :contentHash, CAST(:payload AS jsonb)
                )
                ON CONFLICT (run_id, memory_id) DO NOTHING
                """)
            .param("runId", runId)
            .param("memoryId", message.messageId())
            .param("messageId", message.messageId())
            .param("memoryTier", message.memoryTier().value())
            .param(
                "state",
                message.memoryTier() == MemoryTier.NEGATIVE
                    ? "negative"
                    : "active")
            .param("contentHash", message.contentHash())
            .param("payload", ContractObjectMapper.write(message))
            .update();
    if (inserted == 0) {
      String contentHash =
          jdbc.sql(
                  """
                  SELECT content_hash
                  FROM memory_item
                  WHERE run_id = :runId AND memory_id = :memoryId
                  """)
              .param("runId", runId)
              .param("memoryId", message.messageId())
              .query(String.class)
              .single();
      if (!MessageDigest.isEqual(
          contentHash.getBytes(StandardCharsets.US_ASCII),
          message.contentHash().getBytes(StandardCharsets.US_ASCII))) {
        throw new OptimisticLockException(message.messageId(), 0);
      }
      return;
    }
    insertMemoryDependencies(message);
    String provenanceId =
        CanonicalJson.stableHash(
            List.of(runId, message.messageId(), message.sourceAgentId()));
    jdbc.sql(
            """
            INSERT INTO memory_provenance (
              run_id, provenance_id, memory_id, source_agent_id,
              source_route_id, payload
            ) VALUES (
              :runId, :provenanceId, :memoryId, :sourceAgentId,
              :sourceRouteId, '{}'::jsonb
            )
            ON CONFLICT (run_id, provenance_id) DO NOTHING
            """)
        .param("runId", runId)
        .param("provenanceId", provenanceId)
        .param("memoryId", message.messageId())
        .param("sourceAgentId", message.sourceAgentId())
        .param("sourceRouteId", message.sourceRouteId())
        .update();
  }

  private void insertMemoryDependencies(MessageEnvelope message) {
    List<String> localDependencies =
        message.dependencies().stream()
            .filter(item -> !item.startsWith("external:"))
            .toList();
    if (localDependencies.isEmpty()) {
      return;
    }
    Map<String, String> resolved = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT memory_id, content_hash
            FROM memory_item
            WHERE run_id = :runId
              AND (memory_id IN (:dependencies) OR content_hash IN (:dependencies))
            """)
        .param("runId", runId)
        .param("dependencies", Set.copyOf(localDependencies))
        .query(
            (result, row) ->
                Map.entry(
                    result.getString("memory_id"),
                    result.getString("content_hash")))
        .list()
        .forEach(
            entry -> {
              resolved.put(entry.getKey(), entry.getKey());
              resolved.put(entry.getValue(), entry.getKey());
            });
    List<String> missing =
        localDependencies.stream()
            .filter(item -> !resolved.containsKey(item))
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "unresolved memory dependencies: " + String.join(",", missing));
    }
    for (String dependency : localDependencies) {
      jdbc.sql(
              """
              INSERT INTO memory_dependency (
                run_id, source_memory_id, target_memory_id,
                dependency_type
              ) VALUES (
                :runId, :sourceId, :targetId, 'depends_on'
              )
              ON CONFLICT DO NOTHING
              """)
          .param("runId", runId)
          .param("sourceId", message.messageId())
          .param("targetId", resolved.get(dependency))
          .update();
    }
  }

  private List<String> reopenAffectedObligations(
      List<String> affectedMemoryIds, String batchId) {
    if (affectedMemoryIds.isEmpty()) {
      return List.of();
    }
    List<String> obligationIds =
        jdbc.sql(
                """
                WITH RECURSIVE affected_obligation(obligation_id) AS (
                  SELECT DISTINCT edge.target_ref
                  FROM proof_graph_edge AS edge
                  WHERE edge.run_id = :runId
                    AND edge.relation = 'closes'
                    AND edge.status = 'active'
                    AND edge.source_ref IN (:memoryIds)
                  UNION
                  SELECT edge.source_ref
                  FROM proof_graph_edge AS edge
                  JOIN affected_obligation AS affected
                    ON affected.obligation_id = edge.target_ref
                  WHERE edge.run_id = :runId
                    AND edge.relation = 'depends_on'
                    AND edge.status = 'active'
                )
                SELECT obligation_id
                FROM affected_obligation
                ORDER BY obligation_id
                """)
            .param("runId", runId)
            .param("memoryIds", Set.copyOf(affectedMemoryIds))
            .query(String.class)
            .list();
    if (!obligationIds.isEmpty()) {
      jdbc.sql(
              """
              UPDATE proof_obligation
              SET status = 'open',
                  needs_reverify = true,
                  propagation_batch_id = :batchId,
                  version = version + 1,
                  updated_at = clock_timestamp()
              WHERE run_id = :runId
                AND obligation_id IN (:obligationIds)
              """)
          .param("batchId", batchId)
          .param("runId", runId)
          .param("obligationIds", Set.copyOf(obligationIds))
          .update();
    }
    jdbc.sql(
            """
            UPDATE proof_graph_edge
            SET status = 'invalidated'
            WHERE run_id = :runId
              AND relation = 'closes'
              AND source_ref IN (:memoryIds)
              AND status = 'active'
            """)
        .param("runId", runId)
        .param("memoryIds", Set.copyOf(affectedMemoryIds))
        .update();
    return List.copyOf(obligationIds);
  }

  private List<String> reopenedForBatch(String batchId) {
    return jdbc.sql(
            """
            SELECT obligation_id
            FROM proof_obligation
            WHERE run_id = :runId AND propagation_batch_id = :batchId
            ORDER BY obligation_id
            """)
        .param("runId", runId)
        .param("batchId", batchId)
        .query(String.class)
        .list();
  }

  private void lockRun() {
    jdbc.sql(
            """
            SELECT run_id
            FROM run
            WHERE run_id = :runId
            FOR UPDATE
            """)
        .param("runId", runId)
        .query(String.class)
        .single();
  }

  private static ProofObligation withStatus(
      ProofObligation source, String status, boolean needsReverify) {
    List<String> evidence =
        needsReverify && "open".equals(status)
            ? List.of()
            : source.evidenceMessageIds();
    return new ProofObligation(
        source.assumptions(),
        source.centrality(),
        source.contentHash(),
        source.dependencyIds(),
        source.dependencyRefs(),
        evidence,
        source.firstErrorFingerprint(),
        source.kind(),
        source.normalizedStatement(),
        source.obligationId(),
        source.priority(),
        source.problemHash(),
        source.quantifiers(),
        source.routeIds(),
        source.statement(),
        status);
  }

  private record GraphState(String problemHash, boolean frozen) {}

  private record ObligationRow(
      String id,
      ProofObligation obligation,
      long version,
      boolean needsReverify) {}

  private record MemoryRow(String id, MessageEnvelope message, long version) {}
}
