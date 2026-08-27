package io.github.aililuola.mathproofmesh.verification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Required-reference-first context selection with dependency closure and blind views. */
public final class ContextSelectionPolicy {
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");

  private ContextSelectionPolicy() {}

  public static double evidencePriority(EvidenceType type) {
    return switch (java.util.Objects.requireNonNull(type, "type")) {
      case UNVERIFIED_IDEA -> 0.05;
      case NUMERICAL_HEURISTIC -> 0.15;
      case BOUNDED_EXPERIMENT -> 0.35;
      case EXACT_SYMBOLIC_IDENTITY -> 0.75;
      case COMPLETE_FINITE_ENUMERATION -> 0.85;
      case SAT_SMT_CERTIFICATE, NATURAL_PROOF_AUDITED -> 0.90;
      case COUNTEREXAMPLE -> 0.95;
      case FORMAL_KERNEL_CERTIFICATE -> 1.00;
    };
  }

  public static FactContextSelection select(
      Collection<MessageEnvelope> messages,
      Predicate<String> globallyAdmitted,
      String query,
      int maxChars,
      int maxItems,
      ContextPurpose purpose,
      Collection<String> requiredRefs,
      Map<String, ObjectNode> blindReviewProvenance) {
    java.util.Objects.requireNonNull(messages, "messages");
    java.util.Objects.requireNonNull(globallyAdmitted, "globallyAdmitted");
    ContextPurposePolicy policy = ContextPurposePolicy.forPurpose(purpose);
    List<MessageEnvelope> admitted =
        messages.stream().filter(item -> globallyAdmitted.test(item.messageId())).toList();
    Map<String, MessageEnvelope> byReference = new LinkedHashMap<>();
    for (MessageEnvelope message : admitted) {
      byReference.put(message.messageId(), message);
      byReference.put(message.contentHash(), message);
    }
    LinkedHashSet<String> normalizedRequired = new LinkedHashSet<>();
    if (requiredRefs != null) {
      requiredRefs.stream()
          .filter(java.util.Objects::nonNull)
          .map(String::trim)
          .filter(value -> !value.isEmpty() && !value.startsWith("external:"))
          .forEach(normalizedRequired::add);
    }
    if (maxChars <= 0 || maxItems <= 0 || admitted.isEmpty()) {
      List<String> missing =
          normalizedRequired.stream().filter(ref -> !byReference.containsKey(ref)).toList();
      return new FactContextSelection(
          List.of(), List.of(), missing, 0, Math.max(0, maxChars), !admitted.isEmpty());
    }

    Map<String, Double> centrality = centrality(admitted, byReference);
    Map<String, Integer> order = new HashMap<>();
    for (int index = 0; index < admitted.size(); index++) {
      order.put(admitted.get(index).messageId(), index);
    }
    List<MessageEnvelope> ranked = new ArrayList<>(admitted);
    ranked.sort(
        Comparator.comparingDouble(
                (MessageEnvelope item) ->
                    -score(item, query, purpose, policy, centrality.get(item.messageId())))
            .thenComparingInt(item -> order.get(item.messageId())));

    List<MessageEnvelope> selected = new ArrayList<>();
    List<ObjectNode> packets = new ArrayList<>();
    Set<String> selectedIds = new LinkedHashSet<>();
    List<String> missing = new ArrayList<>();
    int usedChars = 0;

    for (String ref : normalizedRequired) {
      MessageEnvelope required = byReference.get(ref);
      if (required == null) {
        missing.add(ref);
        continue;
      }
      Closure closure = closure(required, byReference, new LinkedHashSet<>());
      if (!closure.complete()
          || !fits(closure.messages(), selectedIds, selected.size(), maxItems)) {
        missing.add(ref);
        missing.addAll(closure.missingRefs());
        continue;
      }
      Addition addition =
          addition(
              closure.messages(),
              selectedIds,
              purpose,
              blindReviewProvenance == null ? Map.of() : blindReviewProvenance);
      if (usedChars + addition.characters() > maxChars) {
        missing.add(ref);
        continue;
      }
      usedChars += append(addition, selected, packets, selectedIds);
    }

    for (MessageEnvelope candidate : ranked) {
      if (selectedIds.contains(candidate.messageId())) {
        continue;
      }
      Closure closure = closure(candidate, byReference, new LinkedHashSet<>());
      if (!closure.complete()
          || !fits(closure.messages(), selectedIds, selected.size(), maxItems)) {
        continue;
      }
      Addition addition =
          addition(
              closure.messages(),
              selectedIds,
              purpose,
              blindReviewProvenance == null ? Map.of() : blindReviewProvenance);
      if (usedChars + addition.characters() > maxChars) {
        continue;
      }
      usedChars += append(addition, selected, packets, selectedIds);
      if (selected.size() >= maxItems) {
        break;
      }
    }
    return new FactContextSelection(
        packets,
        selected.stream().map(MessageEnvelope::messageId).toList(),
        missing.stream().distinct().toList(),
        usedChars,
        maxChars,
        selectedIds.size() < admitted.size() || !missing.isEmpty());
  }

  public static ObjectNode packet(
      MessageEnvelope message,
      ContextPurpose purpose,
      ObjectNode blindReviewProvenance) {
    ContextPurposePolicy policy = ContextPurposePolicy.forPurpose(purpose);
    ObjectNode packet = JsonNodeFactory.instance.objectNode();
    packet.put("context_purpose", purpose.name().toLowerCase(java.util.Locale.ROOT));
    packet.put("message_id", message.messageId());
    packet.put("statement", message.statement());
    packet.put("normalized_statement", message.normalizedStatement());
    packet.set("assumptions", ContractObjectMapper.toTree(message.assumptions()));
    packet.put("conclusion", message.conclusion());
    packet.set("quantifiers", ContractObjectMapper.toTree(message.quantifiers()));
    packet.set("variable_bindings", ContractObjectMapper.toTree(message.variableBindings()));
    packet.set("dependencies", ContractObjectMapper.toTree(message.dependencies()));
    packet.set("scope_limitations", ContractObjectMapper.toTree(message.scopeLimitations()));
    packet.put("evidence_type", message.evidenceType().value());
    packet.put("verification_status", message.verificationStatus().value());
    packet.put("content_hash", message.contentHash());
    if (policy.includeNormalizationConfidence()) {
      packet.put("normalization_confidence", message.normalizationConfidence());
    }
    if (policy.includeReviewProvenance() && blindReviewProvenance != null) {
      packet.set("review_provenance", blindReviewProvenance.deepCopy());
    }
    if (policy.includeRawArtifactRefs()) {
      packet.set("artifact_refs", ContractObjectMapper.toTree(message.artifactRefs()));
    } else if (purpose == ContextPurpose.BLIND_REVIEW && !message.artifactRefs().isEmpty()) {
      ArrayNode evidence = packet.putArray("artifact_evidence");
      for (String ref : message.artifactRefs()) {
        ObjectNode descriptor = evidence.addObject();
        descriptor.put("artifact_content_hash", sha256(ref));
        descriptor.put("certificate_type", message.evidenceType().value());
        descriptor.put("replay_status", replayStatus(message.evidenceType()));
      }
    }
    return packet;
  }

  private static String replayStatus(EvidenceType type) {
    return switch (type) {
      case BOUNDED_EXPERIMENT,
          EXACT_SYMBOLIC_IDENTITY,
          COMPLETE_FINITE_ENUMERATION,
          SAT_SMT_CERTIFICATE,
          FORMAL_KERNEL_CERTIFICATE -> "available_not_replayed_in_packet";
      default -> "not_applicable";
    };
  }

  private static Map<String, Double> centrality(
      List<MessageEnvelope> admitted, Map<String, MessageEnvelope> byReference) {
    Map<String, Integer> incoming = new LinkedHashMap<>();
    admitted.forEach(item -> incoming.put(item.messageId(), 0));
    for (MessageEnvelope message : admitted) {
      for (String dependency : message.dependencies()) {
        MessageEnvelope resolved = byReference.get(dependency);
        if (resolved != null) {
          incoming.computeIfPresent(resolved.messageId(), (ignored, count) -> count + 1);
        }
      }
    }
    int maximum = incoming.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    Map<String, Double> result = new HashMap<>();
    for (MessageEnvelope message : admitted) {
      double incomingScore = maximum == 0 ? 0.0 : (double) incoming.get(message.messageId()) / maximum;
      result.put(
          message.messageId(),
          Math.min(1.0, incomingScore + Math.min(message.targetRouteIds().size(), 4) * 0.05));
    }
    return result;
  }

  private static double score(
      MessageEnvelope message,
      String query,
      ContextPurpose purpose,
      ContextPurposePolicy policy,
      double centrality) {
    StringBuilder text =
        new StringBuilder(message.statement())
            .append(' ')
            .append(message.conclusion())
            .append(' ')
            .append(message.normalizedStatement());
    if (purpose != ContextPurpose.SYNTHESIS) {
      message.assumptions().forEach(item -> text.append(' ').append(item));
      message.scopeLimitations().forEach(item -> text.append(' ').append(item));
    }
    double relevance = jaccard(query, text.toString());
    double confidence =
        (message.verificationConfidence() + message.normalizationConfidence()) / 2.0;
    return policy.relevanceWeight() * relevance
        + policy.evidenceWeight() * evidencePriority(message.evidenceType())
        + policy.confidenceWeight() * confidence
        + policy.centralityWeight() * centrality;
  }

  private static Closure closure(
      MessageEnvelope message,
      Map<String, MessageEnvelope> byReference,
      Set<String> visiting) {
    if (!visiting.add(message.messageId())) {
      return new Closure(List.of(), List.of(message.messageId()), false);
    }
    List<MessageEnvelope> ordered = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String ref : message.dependencies()) {
      if (ref.startsWith("external:")) {
        continue;
      }
      MessageEnvelope dependency = byReference.get(ref);
      if (dependency == null) {
        missing.add(ref);
        continue;
      }
      Closure nested = closure(dependency, byReference, new LinkedHashSet<>(visiting));
      ordered.addAll(nested.messages());
      missing.addAll(nested.missingRefs());
    }
    if (!missing.isEmpty()) {
      return new Closure(List.of(), missing.stream().distinct().toList(), false);
    }
    ordered.add(message);
    Map<String, MessageEnvelope> unique = new LinkedHashMap<>();
    ordered.forEach(item -> unique.putIfAbsent(item.messageId(), item));
    return new Closure(List.copyOf(unique.values()), List.of(), true);
  }

  private static boolean fits(
      List<MessageEnvelope> closure, Set<String> selected, int selectedCount, int maxItems) {
    long additions =
        closure.stream().filter(item -> !selected.contains(item.messageId())).count();
    return selectedCount + additions <= maxItems;
  }

  private static Addition addition(
      List<MessageEnvelope> closure,
      Set<String> selected,
      ContextPurpose purpose,
      Map<String, ObjectNode> provenance) {
    List<MessageEnvelope> additions =
        closure.stream().filter(item -> !selected.contains(item.messageId())).toList();
    List<ObjectNode> packets =
        additions.stream()
            .map(item -> packet(item, purpose, provenance.get(item.messageId())))
            .toList();
    int characters = packets.stream().mapToInt(item -> ContractObjectMapper.write(item).length()).sum();
    return new Addition(additions, packets, characters);
  }

  private static int append(
      Addition addition,
      List<MessageEnvelope> selected,
      List<ObjectNode> packets,
      Set<String> selectedIds) {
    selected.addAll(addition.messages());
    packets.addAll(addition.packets());
    addition.messages().forEach(item -> selectedIds.add(item.messageId()));
    return addition.characters();
  }

  private static double jaccard(String left, String right) {
    Set<String> a = tokens(left);
    Set<String> b = tokens(right);
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    Set<String> intersection = new LinkedHashSet<>(a);
    intersection.retainAll(b);
    Set<String> union = new LinkedHashSet<>(a);
    union.addAll(b);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> tokens(String text) {
    Set<String> result = new LinkedHashSet<>();
    TOKEN.matcher(text == null ? "" : text.toLowerCase(java.util.Locale.ROOT))
        .results()
        .map(java.util.regex.MatchResult::group)
        .forEach(result::add);
    return result;
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record Closure(
      List<MessageEnvelope> messages, List<String> missingRefs, boolean complete) {}

  private record Addition(
      List<MessageEnvelope> messages, List<ObjectNode> packets, int characters) {}
}
