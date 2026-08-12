package io.github.aililuola.mathproofmesh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedSseParserTest {
  @Test
  void parsesBomCommentsMultilineDataAndUnterminatedTail() {
    String input =
        "\ufeffdata: first\r\n"
            + "data: second\r\n"
            + ": heartbeat\r\n"
            + "\r\n"
            + "event: ignored\n"
            + "data: tail";

    assertThat(BoundedSseParser.parseText(input))
        .containsExactly("first\nsecond", "tail");
  }

  @Test
  void fragmentedUtf8InputPreservesEventBoundaries() {
    byte[] bytes =
        "data: \u03b1\n\ndata: \u03b2\n\n"
            .getBytes(StandardCharsets.UTF_8);
    BoundedSseParser parser =
        new BoundedSseParser(
            new ProviderLimits(
                1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));

    assertThat(parser.parse(new OneByteInputStream(bytes), () -> false))
        .containsExactly("\u03b1", "\u03b2");
  }

  @Test
  void rejectsResponseAboveByteLimit() {
    BoundedSseParser parser =
        new BoundedSseParser(
            new ProviderLimits(
                5, Duration.ofSeconds(1), Duration.ofSeconds(1)));

    assertThatThrownBy(
            () ->
                parser.parse(
                    new ByteArrayInputStream(
                        "data: too-large\n\n"
                            .getBytes(StandardCharsets.UTF_8)),
                    () -> false))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            failure ->
                assertThat(((ProviderException) failure).kind())
                    .isEqualTo(ProviderErrorKind.RESPONSE_TOO_LARGE));
  }

  @Test
  void firstChunkAndIdleTimeoutAreDistinctBoundedFailures() {
    BoundedSseParser firstChunk =
        new BoundedSseParser(
            new ProviderLimits(
                1024, Duration.ofMillis(30), Duration.ofSeconds(1)));
    assertThatThrownBy(
            () -> firstChunk.parse(new BlockingInputStream(false), () -> false))
        .isInstanceOf(ProviderException.class)
        .hasMessageContaining("first chunk timeout");

    BoundedSseParser idle =
        new BoundedSseParser(
            new ProviderLimits(
                1024, Duration.ofSeconds(1), Duration.ofMillis(30)));
    assertThatThrownBy(
            () -> idle.parse(new BlockingInputStream(true), () -> false))
        .isInstanceOf(ProviderException.class)
        .hasMessageContaining("stream idle timeout");
  }

  @Test
  void cancellationClosesStreamAndDoesNotReturnPartialData() {
    AtomicBoolean closed = new AtomicBoolean();
    InputStream input =
        new ByteArrayInputStream("data: partial".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        };
    BoundedSseParser parser =
        new BoundedSseParser(
            new ProviderLimits(
                1024, Duration.ofSeconds(1), Duration.ofSeconds(1)));

    assertThatThrownBy(() -> parser.parse(input, () -> true))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            failure ->
                assertThat(((ProviderException) failure).kind())
                    .isEqualTo(ProviderErrorKind.CANCELLED));
    assertThat(closed).isTrue();
  }

  private static final class OneByteInputStream extends InputStream {
    private final byte[] bytes;
    private int offset;

    private OneByteInputStream(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    @Override
    public int read() {
      return offset >= bytes.length ? -1 : Byte.toUnsignedInt(bytes[offset++]);
    }

    @Override
    public int read(byte[] target, int targetOffset, int length) {
      if (offset >= bytes.length) {
        return -1;
      }
      target[targetOffset] = bytes[offset++];
      return 1;
    }
  }

  private static final class BlockingInputStream extends InputStream {
    private final boolean emitsFirst;
    private boolean emitted;
    private volatile boolean closed;

    private BlockingInputStream(boolean emitsFirst) {
      this.emitsFirst = emitsFirst;
    }

    @Override
    public int read() throws IOException {
      if (emitsFirst && !emitted) {
        emitted = true;
        return 'x';
      }
      while (!closed) {
        try {
          Thread.sleep(5);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", exception);
        }
      }
      return -1;
    }

    @Override
    public int read(byte[] target, int offset, int length)
        throws IOException {
      int value = read();
      if (value < 0) {
        return -1;
      }
      target[offset] = (byte) value;
      return 1;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
