package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical stagnation state, keyed by mathematical target and mechanism rather than round. */
public record ZeroGainState(
    Map<TargetMechanismKey, Integer> consecutiveZeroGain,
    int globalZeroGainRounds,
    Set<String> exhaustedMechanismSignatures,
    String stateHash) {

  public ZeroGainState {
    if (globalZeroGainRounds < 0) {
      throw new IllegalArgumentException("globalZeroGainRounds must not be negative");
    }
    List<Map.Entry<TargetMechanismKey, Integer>> entries =
        new ArrayList<>(consecutiveZeroGain == null ? Map.<TargetMechanismKey, Integer>of().entrySet() : consecutiveZeroGain.entrySet());
    entries.sort(Map.Entry.comparingByKey());
    Map<TargetMechanismKey, Integer> ordered = new LinkedHashMap<>();
    for (Map.Entry<TargetMechanismKey, Integer> entry : entries) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException("zero-gain entries must be complete and nonnegative");
      }
      ordered.put(entry.getKey(), entry.getValue());
    }
    consecutiveZeroGain = Collections.unmodifiableMap(ordered);
    List<String> exhausted =
        new ArrayList<>(exhaustedMechanismSignatures == null ? Set.of() : exhaustedMechanismSignatures);
    exhausted.replaceAll(value -> value == null ? "" : value.strip());
    exhausted.removeIf(String::isEmpty);
    exhausted.sort(String::compareTo);
    exhaustedMechanismSignatures = Collections.unmodifiableSet(new LinkedHashSet<>(exhausted));
    String expected = hash(consecutiveZeroGain, globalZeroGainRounds, exhaustedMechanismSignatures);
    stateHash = stateHash == null || stateHash.isBlank() ? expected : stateHash.strip();
    if (!sameHash(expected, stateHash)) {
      throw new IllegalArgumentException("zero-gain state hash mismatch");
    }
  }

  public static ZeroGainState empty() {
    return new ZeroGainState(Map.of(), 0, Set.of(), null);
  }

  public int count(TargetMechanismKey key) {
    return consecutiveZeroGain.getOrDefault(key, 0);
  }

  public ZeroGainState record(TargetMechanismKey key, boolean meaningfulGain, int exhaustAt) {
    if (exhaustAt < 1) {
      throw new IllegalArgumentException("exhaustAt must be positive");
    }
    Map<TargetMechanismKey, Integer> updated = new LinkedHashMap<>(consecutiveZeroGain);
    Set<String> exhausted = new LinkedHashSet<>(exhaustedMechanismSignatures);
    if (meaningfulGain) {
      updated.put(key, 0);
      exhausted.remove(key.mechanismSignature());
    } else {
      int next = Math.addExact(updated.getOrDefault(key, 0), 1);
      updated.put(key, next);
      if (next >= exhaustAt) {
        exhausted.add(key.mechanismSignature());
      }
    }
    return new ZeroGainState(
        updated,
        meaningfulGain ? 0 : Math.addExact(globalZeroGainRounds, 1),
        exhausted,
        null);
  }

  private static String hash(
      Map<TargetMechanismKey, Integer> values, int rounds, Set<String> exhausted) {
    List<Map<String, Object>> rows =
        values.entrySet().stream()
            .map(
                entry ->
                    Map.<String, Object>of(
                        "key", entry.getKey().stableIdentity(), "count", entry.getValue()))
            .toList();
    return CanonicalJson.stableHash(
        Map.of("consecutive_zero_gain", rows, "global_zero_gain_rounds", rounds,
            "exhausted_mechanisms", exhausted));
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
