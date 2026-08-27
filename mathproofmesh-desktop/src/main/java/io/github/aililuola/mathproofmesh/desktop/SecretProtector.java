package io.github.aililuola.mathproofmesh.desktop;

/** Current-user secret protection boundary. */
public interface SecretProtector {
  byte[] protect(byte[] plaintext);

  byte[] unprotect(byte[] ciphertext);

  String protectionId();

  String entropyId();
}
