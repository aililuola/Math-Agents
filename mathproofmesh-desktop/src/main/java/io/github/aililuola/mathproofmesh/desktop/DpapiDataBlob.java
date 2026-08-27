package io.github.aililuola.mathproofmesh.desktop;

import java.util.Arrays;

/** Defensive byte container used at the DPAPI native boundary. */
public final class DpapiDataBlob {
  private final byte[] bytes;

  public DpapiDataBlob(byte[] bytes) {
    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  public byte[] bytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }

  public int size() {
    return bytes.length;
  }
}
