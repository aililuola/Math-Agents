package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.FormalCertificateRef;
import io.github.aililuola.mathproofmesh.contract.FormalStatementPacket;

/** Optional formal backend boundary. Lean remains disabled unless explicitly supplied. */
public interface FormalVerifierBackend {
  String name();

  boolean available();

  FormalCertificateRef verify(FormalStatementPacket packet);
}
