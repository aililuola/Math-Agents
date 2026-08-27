package io.github.aililuola.mathproofmesh.contract;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

public final class PythonCompatibleIdGenerator {
  private static final int MAX_ATTEMPTS = 5;

  private PythonCompatibleIdGenerator() {}

  public static String newId(String prefix) {
    return newId(prefix, ignored -> false);
  }

  public static String newId(String prefix, Predicate<String> alreadyExists) {
    String normalizedPrefix = ContractStrings.required("prefix", ContractStrings.trim(prefix));
    Objects.requireNonNull(alreadyExists, "alreadyExists");
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
      String candidate = normalizedPrefix + "_" + suffix;
      if (!alreadyExists.test(candidate)) {
        return candidate;
      }
    }
    throw new ContractValidationException(
        "could not allocate a unique " + normalizedPrefix + " ID after " + MAX_ATTEMPTS + " attempts");
  }
}
