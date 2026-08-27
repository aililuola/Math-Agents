package io.github.aililuola.mathproofmesh.desktop;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/** Windows DPAPI protection scoped to the current user, with fixed application entropy. */
public final class WindowsDpapiProtector implements SecretProtector {
  private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
  private static final byte[] ENTROPY =
      "MathProofMesh Desktop credentials v1".getBytes(StandardCharsets.UTF_8);
  private static final String ENTROPY_ID = "sha256:" + hexSha256(ENTROPY);

  public WindowsDpapiProtector() {
    if (!isWindows()) {
      throw new IllegalStateException("Windows DPAPI is available only on Windows");
    }
  }

  @Override
  public byte[] protect(byte[] plaintext) {
    byte[] input = Arrays.copyOf(plaintext, plaintext.length);
    try {
      return Crypt32Util.cryptProtectData(
          input,
          ENTROPY,
          CRYPTPROTECT_UI_FORBIDDEN,
          "MathProofMesh API credentials",
          new WinCrypt.CRYPTPROTECT_PROMPTSTRUCT());
    } finally {
      Arrays.fill(input, (byte) 0);
    }
  }

  @Override
  public byte[] unprotect(byte[] ciphertext) {
    byte[] input = Arrays.copyOf(ciphertext, ciphertext.length);
    try {
      return Crypt32Util.cryptUnprotectData(
          input, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, new WinCrypt.CRYPTPROTECT_PROMPTSTRUCT());
    } finally {
      Arrays.fill(input, (byte) 0);
    }
  }

  @Override
  public String protectionId() {
    return "windows-dpapi-current-user";
  }

  @Override
  public String entropyId() {
    return ENTROPY_ID;
  }

  public boolean currentUserScopeOnly() {
    return true;
  }

  public static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  private static String hexSha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
