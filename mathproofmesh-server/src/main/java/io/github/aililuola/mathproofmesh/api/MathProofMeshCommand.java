package io.github.aililuola.mathproofmesh.api;

import io.github.aililuola.mathproofmesh.MathProofMeshApplication;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "mathproofmesh",
    mixinStandardHelpOptions = true,
    version = "MathProofMesh 0.8.0",
    description = "Solve and inspect mathematical proof runs.")
public final class MathProofMeshCommand implements Callable<Integer> {
  @Override
  public Integer call() {
    return 0;
  }

  public static CommandLine commandLine(
      RunApiService service, PrintWriter output, PrintWriter error, ServerLauncher launcher) {
    CommandLine line = new CommandLine(new MathProofMeshCommand());
    line.setOut(output);
    line.setErr(error);
    line.addSubcommand("solve", new SolveCommand(service, output));
    line.addSubcommand("resume", new ResumeCommand(service, output));
    line.addSubcommand("demo", new DemoCommand(service, output));
    line.addSubcommand("probe", new ProbeCommand(output));
    line.addSubcommand("serve", new ServeCommand(output, launcher));
    return line;
  }

  public static CommandLine commandLine(RunApiService service, PrintWriter output, PrintWriter error) {
    return commandLine(service, output, error, MathProofMeshCommand::launchServer);
  }

  public static void main(String[] args) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RunApiService service = new RunApiService(new ApiObservability(registry), "target/api-runs", 8);
    int exit =
        commandLine(service, new PrintWriter(System.out, true), new PrintWriter(System.err, true))
            .execute(args);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  @Command(name = "solve", description = "Solve a problem file.")
  static final class SolveCommand implements Callable<Integer> {
    private final RunApiService service;
    private final PrintWriter output;

    @Parameters(index = "0", description = "UTF-8 problem file")
    private Path problemFile;

    @Option(names = "--config", required = true)
    private Path config;

    @Option(names = "--run-id")
    private String runId;

    SolveCommand(RunApiService service, PrintWriter output) {
      this.service = service;
      this.output = output;
    }

    @Override
    public Integer call() {
      validateConfig(config);
      String problem = readBounded(problemFile, 100_000);
      TraceContext.Scope scope = TraceContext.bind(null);
      try {
        output.println(ContractObjectMapper.write(service.solve(new SolveRequest(problem, runId, null))));
      } finally {
        scope.close();
      }
      return 0;
    }
  }

  @Command(name = "resume", description = "Resume a committed run.")
  static final class ResumeCommand implements Callable<Integer> {
    private final RunApiService service;
    private final PrintWriter output;

    @Parameters(index = "0")
    private String runId;

    @Option(names = "--config", required = true)
    private Path config;

    ResumeCommand(RunApiService service, PrintWriter output) {
      this.service = service;
      this.output = output;
    }

    @Override
    public Integer call() {
      validateConfig(config);
      TraceContext.Scope scope = TraceContext.bind(null);
      try {
        output.println(ContractObjectMapper.write(service.resume(new ResumeRequest(runId))));
      } finally {
        scope.close();
      }
      return 0;
    }
  }

  @Command(name = "demo", description = "Run a deterministic provider-free demo.")
  static final class DemoCommand implements Callable<Integer> {
    private final RunApiService service;
    private final PrintWriter output;

    @Option(names = "--run-id", defaultValue = "demo-run")
    private String runId;

    DemoCommand(RunApiService service, PrintWriter output) {
      this.service = service;
      this.output = output;
    }

    @Override
    public Integer call() {
      TraceContext.Scope scope = TraceContext.bind(null);
      try {
        output.println(ContractObjectMapper.write(service.demo(runId)));
      } finally {
        scope.close();
      }
      return 0;
    }
  }

  @Command(name = "probe", description = "Probe configured provider wiring without exposing secrets.")
  static final class ProbeCommand implements Callable<Integer> {
    private final PrintWriter output;

    @Option(names = "--config", required = true)
    private Path config;

    @Option(names = "--completion")
    private boolean completion;

    ProbeCommand(PrintWriter output) {
      this.output = output;
    }

    @Override
    public Integer call() {
      validateConfig(config);
      output.println(ContractObjectMapper.write(MockDemoFunctions.probe(completion)));
      return 0;
    }
  }

  @Command(name = "serve", description = "Start the loopback HTTP service.")
  static final class ServeCommand implements Callable<Integer> {
    private final PrintWriter output;
    private final ServerLauncher launcher;

    @Option(names = "--config", required = true)
    private Path config;

    @Option(names = "--host", defaultValue = "127.0.0.1")
    private String host;

    @Option(names = "--port", defaultValue = "8080")
    private int port;

    ServeCommand(PrintWriter output, ServerLauncher launcher) {
      this.output = output;
      this.launcher = launcher;
    }

    @Override
    public Integer call() {
      validateConfig(config);
      String safeHost = ActivitySanitizer.identifier(host, 64);
      if (!("127.0.0.1".equals(safeHost)
          || "localhost".equals(safeHost)
          || "::1".equals(safeHost))) {
        throw new CommandLine.ParameterException(
            new CommandLine(this), "serve host must be loopback");
      }
      if (port < 0 || port > 65_535) {
        throw new CommandLine.ParameterException(new CommandLine(this), "port must be in [0,65535]");
      }
      launcher.launch(config, safeHost, port);
      output.println(
          ContractObjectMapper.write(
              Map.of("status", "serving", "host", safeHost, "port", port)));
      return 0;
    }
  }

  private static void launchServer(Path config, String host, int port) {
    SpringApplication application = new SpringApplication(MathProofMeshApplication.class);
    application.setDefaultProperties(
        Map.of(
            "server.address", host,
            "server.port", port,
            "mathproofmesh.config-path", config.toAbsolutePath().normalize().toString()));
    application.run();
  }

  private static void validateConfig(Path config) {
    if (config == null
        || Files.isSymbolicLink(config)
        || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("configuration file does not exist or is unsafe");
    }
    try {
      if (Files.size(config) > 1_048_576L) {
        throw new IllegalArgumentException("configuration file is too large");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("configuration file could not be inspected", exception);
    }
  }

  private static String readBounded(Path path, long maxBytes) {
    if (path == null
        || Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("problem file does not exist or is unsafe");
    }
    try {
      if (Files.size(path) > maxBytes) {
        throw new IllegalArgumentException("problem file is too large");
      }
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("problem file could not be read", exception);
    }
  }

  @FunctionalInterface
  public interface ServerLauncher {
    void launch(Path config, String host, int port);
  }
}
