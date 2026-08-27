package io.github.aililuola.mathproofmesh.persistence;

import io.github.aililuola.mathproofmesh.communication.MessageDelivery;
import io.github.aililuola.mathproofmesh.communication.MessageDeliveryState;
import io.github.aililuola.mathproofmesh.communication.InvalidatedDelivery;
import io.github.aililuola.mathproofmesh.communication.MessageAdmissionPolicy;
import io.github.aililuola.mathproofmesh.communication.MessageRepository;
import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.communication.MessageUtilityRecord;
import io.github.aililuola.mathproofmesh.communication.PromptDeliveryBatch;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import io.github.aililuola.mathproofmesh.contract.MessageReceipt;
import io.github.aililuola.mathproofmesh.contract.ReceiptStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcMessageRepository implements MessageRepository {
  private static final String FIND_MESSAGE_SQL =
      """
      SELECT payload::text
      FROM message
      WHERE run_id = :runId AND message_id = :messageId
      """;
  private static final String FIND_DEDUPE_SQL =
      """
      SELECT payload::text
      FROM message
      WHERE run_id = :runId AND dedupe_key = :dedupeKey
      """;
  private static final String FIND_DELIVERY_SQL =
      """
      SELECT delivery_key, message_id, target_route_id, state, priority_name,
             delivered_round, processing_opportunities, provider_request_id,
             receipt_token, actually_used, version
      FROM message_delivery
      WHERE run_id = :runId AND delivery_key = :deliveryKey
      """;
  private static final String INSERT_MESSAGE_SQL =
      """
      INSERT INTO message (
        run_id, message_id, content_hash, dedupe_key, source_agent_id,
        source_route_id, message_type, priority, round_index, ttl_rounds, payload
      ) VALUES (
        :runId, :messageId, :contentHash, :dedupeKey, :sourceAgentId,
        :sourceRouteId, :messageType, :priority, :roundIndex, :ttlRounds,
        CAST(:payload AS jsonb)
      )
      ON CONFLICT (run_id, message_id) DO NOTHING
      """;
  private static final String INSERT_DELIVERY_SQL =
      """
      INSERT INTO message_delivery (
        run_id, delivery_key, message_id, target_route_id, state,
        priority_name, delivered_round, processing_opportunities,
        provider_request_id, receipt_token, actually_used, payload
      ) VALUES (
        :runId, :deliveryKey, :messageId, :targetRouteId, :state,
        :priorityName, :deliveredRound, :processingOpportunities,
        NULLIF(:providerRequestId, ''), :receiptToken, :actuallyUsed,
        '{}'::jsonb
      )
      ON CONFLICT (run_id, delivery_key) DO NOTHING
      """;
  private static final String INSERT_OUTBOX_SQL =
      """
      INSERT INTO outbox_event (
        event_id, run_id, aggregate_type, aggregate_id, aggregate_version,
        event_type, payload
      ) VALUES (
        :eventId, :runId, 'message', :messageId, 0, :eventType,
        CAST(:payload AS jsonb)
      )
      ON CONFLICT (event_id) DO NOTHING
      """;
  private static final String SELECT_FOR_PROMPT_SQL =
      """
      SELECT delivery_key, message_id, target_route_id, state, priority_name,
             delivered_round, processing_opportunities, provider_request_id,
             receipt_token, actually_used, version
      FROM message_delivery
      WHERE run_id = :runId
        AND target_route_id = :targetRouteId
        AND (
          state IN ('queued', 'delivered')
          OR (state = 'deferred' AND delivered_round <= :currentRound)
        )
      ORDER BY
        CASE priority_name
          WHEN 'critical' THEN 0
          WHEN 'high' THEN 1
          WHEN 'normal' THEN 2
          ELSE 3
        END,
        delivered_round,
        delivery_key
      FOR UPDATE SKIP LOCKED
      LIMIT :limit
      """;

  private final String runId;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public JdbcMessageRepository(
      String runId, JdbcClient jdbc, TransactionTemplate transactions) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId is required");
    }
    this.runId = runId;
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public Optional<MessageEnvelope> findMessage(String messageId) {
    return jdbc.sql(FIND_MESSAGE_SQL)
        .param("runId", runId)
        .param("messageId", messageId)
        .query(String.class)
        .optional()
        .map(json -> ContractObjectMapper.read(json, MessageEnvelope.class));
  }

  @Override
  public Optional<MessageEnvelope> findByDedupeKey(String dedupeKey) {
    return jdbc.sql(FIND_DEDUPE_SQL)
        .param("runId", runId)
        .param("dedupeKey", dedupeKey)
        .query(String.class)
        .optional()
        .map(json -> ContractObjectMapper.read(json, MessageEnvelope.class));
  }

  @Override
  public Optional<MessageDelivery> findDelivery(String deliveryKey) {
    return jdbc.sql(FIND_DELIVERY_SQL)
        .param("runId", runId)
        .param("deliveryKey", deliveryKey)
        .query(JdbcMessageRepository::mapDelivery)
        .optional();
  }

  @Override
  public List<MessageDelivery> deliveriesForMessage(String messageId) {
    return jdbc.sql(
            """
            SELECT delivery_key, message_id, target_route_id, state, priority_name,
                   delivered_round, processing_opportunities, provider_request_id,
                   receipt_token, actually_used, version
            FROM message_delivery
            WHERE run_id = :runId AND message_id = :messageId
            ORDER BY delivery_key
            """)
        .param("runId", runId)
        .param("messageId", messageId)
        .query(JdbcMessageRepository::mapDelivery)
        .list();
  }

  @Override
  public long countDeliveries(
      String targetRouteId, int deliveredRound, Set<MessagePriority> priorities) {
    return countDeliveries(targetRouteId, deliveredRound, priorityNames(priorities));
  }

  @Override
  public long countDeliveries(int deliveredRound, Set<MessagePriority> priorities) {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM message_delivery
            WHERE run_id = :runId
              AND delivered_round = :deliveredRound
              AND priority_name IN (:priorities)
            """)
        .param("runId", runId)
        .param("deliveredRound", deliveredRound)
        .param("priorities", priorityNames(priorities))
        .query(Long.class)
        .single();
  }

  @Override
  public void saveAccepted(
      MessageEnvelope message,
      String dedupeKey,
      Collection<MessageDelivery> deliveries,
      String admittedEventPayload) {
    transactions.executeWithoutResult(
        ignored -> {
          int inserted =
              jdbc.sql(INSERT_MESSAGE_SQL)
                  .param("runId", runId)
                  .param("messageId", message.messageId())
                  .param("contentHash", message.contentHash())
                  .param("dedupeKey", dedupeKey)
                  .param("sourceAgentId", message.sourceAgentId())
                  .param("sourceRouteId", message.sourceRouteId())
                  .param("messageType", message.messageType().value())
                  .param(
                      "priority",
                      priorityNumber(MessageAdmissionPolicy.priority(message)))
                  .param("roundIndex", message.roundCreated())
                  .param("ttlRounds", message.ttlRounds())
                  .param("payload", ContractObjectMapper.write(message))
                  .update();
          if (inserted == 0
              && findMessage(message.messageId())
                  .filter(existing -> existing.contentHash().equals(message.contentHash()))
                  .isEmpty()) {
            throw new PersistenceException("message identity conflict");
          }
          insertDeliveries(deliveries);
          insertOutbox(
              "message-admitted:" + message.messageId(),
              message.messageId(),
              "message_admitted",
              admittedEventPayload);
        });
  }

  @Override
  public void addDeliveries(
      String messageId,
      Collection<MessageDelivery> deliveries,
      String admittedEventPayload) {
    transactions.executeWithoutResult(
        ignored -> {
          if (findMessage(messageId).isEmpty()) {
            throw new PersistenceException("unknown message '" + messageId + "'");
          }
          insertDeliveries(deliveries);
          for (MessageDelivery delivery : deliveries) {
            insertOutbox(
                "message-delivery:" + delivery.deliveryKey(),
                messageId,
                "message_delivery_queued",
                admittedEventPayload);
          }
        });
  }

  @Override
  public List<MessageDelivery> stageDeliveries(
      String targetRouteId, int currentRound, int limit) {
    return java.util.Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              List<MessageDelivery> selected =
                  selectForPrompt(targetRouteId, currentRound, limit);
              List<MessageDelivery> staged = new ArrayList<>();
              for (MessageDelivery delivery : selected) {
                jdbc.sql(
                        """
                        UPDATE message_delivery
                        SET state = 'delivered',
                            delivered_at = COALESCE(delivered_at, clock_timestamp()),
                            version = version + 1,
                            updated_at = clock_timestamp()
                        WHERE run_id = :runId AND delivery_key = :deliveryKey
                          AND state IN ('queued', 'deferred')
                        """)
                    .param("runId", runId)
                    .param("deliveryKey", delivery.deliveryKey())
                    .update();
                staged.add(
                    findDelivery(delivery.deliveryKey()).orElseThrow());
              }
              return List.copyOf(staged);
            }),
        "stage delivery transaction result");
  }

  @Override
  public PromptDeliveryBatch consumeForPrompt(
      String targetRouteId, String providerRequestId, int currentRound, int limit) {
    return java.util.Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              int inserted =
                  jdbc.sql(
                          """
                          INSERT INTO provider_prompt_request (
                            run_id, request_id, target_route_id, state
                          ) VALUES (
                            :runId, :requestId, :targetRouteId, 'preparing'
                          )
                          ON CONFLICT (run_id, request_id) DO NOTHING
                          """)
                      .param("runId", runId)
                      .param("requestId", providerRequestId)
                      .param("targetRouteId", targetRouteId)
                      .update();
              if (inserted == 0) {
                return new PromptDeliveryBatch(
                    providerRequestId, targetRouteId, List.of(), List.of(), true);
              }
              List<MessageDelivery> selected =
                  selectForPrompt(targetRouteId, currentRound, limit);
              List<MessageDelivery> consumed = new ArrayList<>();
              for (MessageDelivery delivery : selected) {
                int updated =
                    jdbc.sql(
                            """
                            UPDATE message_delivery
                            SET state = 'prompt_consumed',
                                delivered_at = COALESCE(delivered_at, clock_timestamp()),
                                prompt_consumed_at = clock_timestamp(),
                                provider_request_id = :requestId,
                                processing_opportunities = processing_opportunities + 1,
                                version = version + 1,
                                updated_at = clock_timestamp()
                            WHERE run_id = :runId AND delivery_key = :deliveryKey
                              AND state IN ('queued', 'delivered', 'deferred')
                            """)
                        .param("runId", runId)
                        .param("requestId", providerRequestId)
                        .param("deliveryKey", delivery.deliveryKey())
                        .update();
                if (updated != 1) {
                  throw new OptimisticLockException(delivery.deliveryKey(), delivery.version());
                }
                consumed.add(findDelivery(delivery.deliveryKey()).orElseThrow());
              }
              String payload =
                  ContractObjectMapper.write(
                      Map.of(
                          "delivery_keys",
                          consumed.stream().map(MessageDelivery::deliveryKey).toList()));
              jdbc.sql(
                      """
                      UPDATE provider_prompt_request
                      SET state = 'prepared',
                          payload = CAST(:payload AS jsonb),
                          updated_at = clock_timestamp()
                      WHERE run_id = :runId AND request_id = :requestId
                      """)
                  .param("payload", payload)
                  .param("runId", runId)
                  .param("requestId", providerRequestId)
                  .update();
              List<MessageEnvelope> messages =
                  consumed.stream()
                      .map(delivery -> findMessage(delivery.messageId()).orElseThrow())
                      .toList();
              return new PromptDeliveryBatch(
                  providerRequestId, targetRouteId, messages, consumed, false);
            }),
        "prompt consumption transaction result");
  }

  @Override
  public MessageReceipt saveReceipt(String deliveryKey, MessageReceipt receipt) {
    return java.util.Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              Optional<MessageReceipt> existing = findReceipt(deliveryKey);
              if (existing.isPresent()) {
                return existing.orElseThrow();
              }
              jdbc.sql(
                      """
                      INSERT INTO message_receipt (
                        run_id, receipt_id, delivery_key, status, semantic_hash, payload
                      ) VALUES (
                        :runId, :receiptId, :deliveryKey, :status,
                        NULLIF(:semanticHash, ''), CAST(:payload AS jsonb)
                      )
                      """)
                  .param("runId", runId)
                  .param("receiptId", receipt.receiptId())
                  .param("deliveryKey", deliveryKey)
                  .param("status", receipt.status().value())
                  .param("semanticHash", receipt.semanticHash())
                  .param("payload", ContractObjectMapper.write(receipt))
                  .update();
              String state =
                  receipt.status() == ReceiptStatus.ACCEPTED
                      ? MessageDeliveryState.ACKNOWLEDGED.wireValue()
                      : MessageDeliveryState.REJECTED.wireValue();
              jdbc.sql(
                      """
                      UPDATE message_delivery
                      SET state = :state,
                          acknowledged_at = clock_timestamp(),
                          version = version + 1,
                          updated_at = clock_timestamp()
                      WHERE run_id = :runId AND delivery_key = :deliveryKey
                      """)
                  .param("state", state)
                  .param("runId", runId)
                  .param("deliveryKey", deliveryKey)
                  .update();
              insertOutbox(
                  "message-receipt:" + deliveryKey,
                  receipt.messageId(),
                  "message_acknowledged",
                  ContractObjectMapper.write(receipt));
              return receipt;
            }),
        "receipt transaction result");
  }

  @Override
  public Optional<MessageReceipt> findReceipt(String deliveryKey) {
    return jdbc.sql(
            """
            SELECT payload::text
            FROM message_receipt
            WHERE run_id = :runId AND delivery_key = :deliveryKey
              AND superseded_at IS NULL
            """)
        .param("runId", runId)
        .param("deliveryKey", deliveryKey)
        .query(String.class)
        .optional()
        .map(json -> ContractObjectMapper.read(json, MessageReceipt.class));
  }

  @Override
  public void saveUtility(MessageUtilityRecord utility) {
    transactions.executeWithoutResult(
        ignored -> {
          String utilityId =
              CanonicalJson.stableHash(List.of(runId, utility.deliveryKey(), "utility"));
          jdbc.sql(
                  """
                  INSERT INTO message_utility (
                    run_id, utility_id, delivery_key, claimed_utility,
                    verified_utility, status, payload
                  ) VALUES (
                    :runId, :utilityId, :deliveryKey, 'receipt_claim',
                    :verifiedUtility, 'verified', CAST(:payload AS jsonb)
                  )
                  ON CONFLICT (run_id, utility_id) DO NOTHING
                  """)
              .param("runId", runId)
              .param("utilityId", utilityId)
              .param("deliveryKey", utility.deliveryKey())
              .param("verifiedUtility", Double.toString(utility.score()))
              .param("payload", ContractObjectMapper.write(utility))
              .update();
          jdbc.sql(
                  """
                  UPDATE message_delivery
                  SET actually_used = true,
                      version = version + 1,
                      updated_at = clock_timestamp()
                  WHERE run_id = :runId AND delivery_key = :deliveryKey
                  """)
              .param("runId", runId)
              .param("deliveryKey", utility.deliveryKey())
              .update();
        });
  }

  @Override
  public Optional<MessageUtilityRecord> findUtility(String deliveryKey) {
    return jdbc.sql(
            """
            SELECT payload::text
            FROM message_utility
            WHERE run_id = :runId AND delivery_key = :deliveryKey
              AND status = 'verified'
            ORDER BY created_at DESC
            LIMIT 1
            """)
        .param("runId", runId)
        .param("deliveryKey", deliveryKey)
        .query(String.class)
        .optional()
        .map(json -> ContractObjectMapper.read(json, MessageUtilityRecord.class));
  }

  @Override
  public List<InvalidatedDelivery> invalidateMessages(
      Collection<String> messageIds, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("invalidation reason is required");
    }
    Set<String> selectedIds = Set.copyOf(messageIds);
    if (selectedIds.isEmpty()) {
      return List.of();
    }
    return java.util.Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              List<MessageDelivery> selected =
                  jdbc.sql(
                          """
                          SELECT delivery_key, message_id, target_route_id, state,
                                 priority_name, delivered_round,
                                 processing_opportunities, provider_request_id,
                                 receipt_token, actually_used, version
                          FROM message_delivery
                          WHERE run_id = :runId AND message_id IN (:messageIds)
                          ORDER BY delivery_key
                          FOR UPDATE
                          """)
                      .param("runId", runId)
                      .param("messageIds", selectedIds)
                      .query(JdbcMessageRepository::mapDelivery)
                      .list();
              List<InvalidatedDelivery> archived =
                  selected.stream()
                      .map(delivery -> InvalidatedDelivery.of(delivery, reason))
                      .toList();
              for (InvalidatedDelivery invalidated : archived) {
                MessageDelivery delivery = invalidated.delivery();
                insertOutbox(
                    "message-invalidated:" + delivery.deliveryKey(),
                    delivery.messageId(),
                    "message_delivery_invalidated",
                    ContractObjectMapper.write(invalidated));
                jdbc.sql(
                        """
                        DELETE FROM message_utility
                        WHERE run_id = :runId AND delivery_key = :deliveryKey
                        """)
                    .param("runId", runId)
                    .param("deliveryKey", delivery.deliveryKey())
                    .update();
                jdbc.sql(
                        """
                        DELETE FROM message_receipt
                        WHERE run_id = :runId AND delivery_key = :deliveryKey
                        """)
                    .param("runId", runId)
                    .param("deliveryKey", delivery.deliveryKey())
                    .update();
                jdbc.sql(
                        """
                        DELETE FROM message_delivery
                        WHERE run_id = :runId AND delivery_key = :deliveryKey
                        """)
                    .param("runId", runId)
                    .param("deliveryKey", delivery.deliveryKey())
                    .update();
              }
              return List.copyOf(archived);
            }),
        "message invalidation transaction result");
  }

  @Override
  public List<String> expire(int currentRound) {
    return jdbc.sql(
            """
            UPDATE message_delivery AS delivery
            SET state = 'expired',
                version = delivery.version + 1,
                updated_at = clock_timestamp()
            FROM message
            WHERE delivery.run_id = :runId
              AND message.run_id = delivery.run_id
              AND message.message_id = delivery.message_id
              AND delivery.state IN ('queued', 'delivered', 'deferred')
              AND :currentRound - message.round_index > message.ttl_rounds
            RETURNING delivery.delivery_key
            """)
        .param("runId", runId)
        .param("currentRound", currentRound)
        .query(String.class)
        .list();
  }

  @Override
  public MessageStoreSnapshot snapshot() {
    Map<String, MessageEnvelope> messages = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT message_id, payload::text
            FROM message WHERE run_id = :runId ORDER BY message_id
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                Map.entry(
                    result.getString("message_id"),
                    ContractObjectMapper.read(
                        result.getString("payload"), MessageEnvelope.class)))
        .list()
        .forEach(entry -> messages.put(entry.getKey(), entry.getValue()));
    Map<String, String> dedupe = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT dedupe_key, message_id
            FROM message WHERE run_id = :runId ORDER BY dedupe_key
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                Map.entry(
                    result.getString("dedupe_key"), result.getString("message_id")))
        .list()
        .forEach(entry -> dedupe.put(entry.getKey(), entry.getValue()));
    Map<String, MessageDelivery> deliveries = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT delivery_key, message_id, target_route_id, state, priority_name,
                   delivered_round, processing_opportunities, provider_request_id,
                   receipt_token, actually_used, version
            FROM message_delivery WHERE run_id = :runId ORDER BY delivery_key
            """)
        .param("runId", runId)
        .query(JdbcMessageRepository::mapDelivery)
        .list()
        .forEach(delivery -> deliveries.put(delivery.deliveryKey(), delivery));
    Map<String, MessageReceipt> receipts = new LinkedHashMap<>();
    deliveries.keySet().forEach(
        key -> findReceipt(key).ifPresent(receipt -> receipts.put(key, receipt)));
    Map<String, MessageUtilityRecord> utilities = new LinkedHashMap<>();
    deliveries.keySet().forEach(
        key -> findUtility(key).ifPresent(utility -> utilities.put(key, utility)));
    Map<String, List<String>> requests = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT request_id, payload::text
            FROM provider_prompt_request WHERE run_id = :runId ORDER BY request_id
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                Map.entry(
                    result.getString("request_id"),
                    parseDeliveryKeys(result.getString("payload"))))
        .list()
        .forEach(entry -> requests.put(entry.getKey(), entry.getValue()));
    Map<String, String> events = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT event_id, payload::text
            FROM outbox_event
            WHERE run_id = :runId AND aggregate_type = 'message'
            ORDER BY event_id
            """)
        .param("runId", runId)
        .query(
            (result, row) ->
                Map.entry(result.getString("event_id"), result.getString("payload")))
        .list()
        .forEach(entry -> events.put(entry.getKey(), entry.getValue()));
    Map<String, InvalidatedDelivery> invalidatedDeliveries = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT payload::text
            FROM outbox_event
            WHERE run_id = :runId
              AND aggregate_type = 'message'
              AND event_type = 'message_delivery_invalidated'
            ORDER BY created_at, event_id
            """)
        .param("runId", runId)
        .query(String.class)
        .list()
        .stream()
        .map(json -> ContractObjectMapper.read(json, InvalidatedDelivery.class))
        .forEach(
            invalidated ->
                invalidatedDeliveries.put(
                    invalidated.delivery().deliveryKey(), invalidated));
    return new MessageStoreSnapshot(
        messages,
        dedupe,
        deliveries,
        receipts,
        utilities,
        requests,
        events,
        invalidatedDeliveries);
  }

  private long countDeliveries(
      String targetRouteId, int deliveredRound, List<String> priorities) {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM message_delivery
            WHERE run_id = :runId
              AND target_route_id = :targetRouteId
              AND delivered_round = :deliveredRound
              AND priority_name IN (:priorities)
            """)
        .param("runId", runId)
        .param("targetRouteId", targetRouteId)
        .param("deliveredRound", deliveredRound)
        .param("priorities", priorities)
        .query(Long.class)
        .single();
  }

  private List<MessageDelivery> selectForPrompt(
      String targetRouteId, int currentRound, int limit) {
    return jdbc.sql(SELECT_FOR_PROMPT_SQL)
        .param("runId", runId)
        .param("targetRouteId", targetRouteId)
        .param("currentRound", currentRound)
        .param("limit", limit)
        .query(JdbcMessageRepository::mapDelivery)
        .list();
  }

  private void insertDeliveries(Collection<MessageDelivery> deliveries) {
    for (MessageDelivery delivery : deliveries) {
      jdbc.sql(INSERT_DELIVERY_SQL)
          .param("runId", runId)
          .param("deliveryKey", delivery.deliveryKey())
          .param("messageId", delivery.messageId())
          .param("targetRouteId", delivery.targetRouteId())
          .param("state", delivery.state().wireValue())
          .param("priorityName", delivery.priority().value())
          .param("deliveredRound", delivery.deliveredRound())
          .param("processingOpportunities", delivery.processingOpportunities())
          .param("providerRequestId", delivery.providerRequestId())
          .param("receiptToken", delivery.receiptToken())
          .param("actuallyUsed", delivery.actuallyUsed())
          .update();
    }
  }

  private void insertOutbox(
      String eventIdentity, String messageId, String eventType, String payload) {
    jdbc.sql(INSERT_OUTBOX_SQL)
        .param("eventId", CanonicalJson.stableHash(List.of(runId, eventIdentity)))
        .param("runId", runId)
        .param("messageId", messageId)
        .param("eventType", eventType)
        .param("payload", payload)
        .update();
  }

  private static MessageDelivery mapDelivery(ResultSet result, int rowNumber)
      throws SQLException {
    String requestId = result.getString("provider_request_id");
    return new MessageDelivery(
        result.getString("delivery_key"),
        result.getString("message_id"),
        result.getString("target_route_id"),
        MessageDeliveryState.valueOf(result.getString("state").toUpperCase(java.util.Locale.ROOT)),
        MessagePriority.fromValue(result.getString("priority_name")),
        result.getInt("delivered_round"),
        result.getInt("processing_opportunities"),
        requestId == null ? "" : requestId,
        result.getString("receipt_token"),
        result.getBoolean("actually_used"),
        result.getLong("version"));
  }

  private static int priorityNumber(MessagePriority priority) {
    return switch (priority) {
      case CRITICAL -> 3;
      case HIGH -> 2;
      case NORMAL -> 1;
      case LOW -> 0;
    };
  }

  private static List<String> priorityNames(Set<MessagePriority> priorities) {
    Set<MessagePriority> values =
        priorities.isEmpty() ? EnumSet.noneOf(MessagePriority.class) : EnumSet.copyOf(priorities);
    return values.stream().map(MessagePriority::value).toList();
  }

  private static List<String> parseDeliveryKeys(String payload) {
    var node = ContractObjectMapper.parseTree(payload).path("delivery_keys");
    if (!node.isArray()) {
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    node.forEach(item -> keys.add(item.asText()));
    return List.copyOf(keys);
  }
}
