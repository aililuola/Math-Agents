package io.github.aililuola.mathproofmesh.desktop;

import java.util.List;
import java.util.Map;

public record CredentialsRequest(Map<String, String> values, List<String> clear, boolean persist) {
  public CredentialsRequest {
    values = values == null ? Map.of() : Map.copyOf(values);
    clear = clear == null ? List.of() : List.copyOf(clear);
    if (values.size() > 5 || clear.size() > 5) {
      throw new IllegalArgumentException("credential request exceeds the supported key count");
    }
  }
}
