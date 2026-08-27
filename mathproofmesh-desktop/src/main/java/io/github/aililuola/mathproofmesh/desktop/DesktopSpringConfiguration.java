package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aililuola.mathproofmesh.api.RunApiService;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans activated only for the embedded desktop server. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mathproofmesh.desktop.enabled", havingValue = "true")
public class DesktopSpringConfiguration {
  @Bean
  DesktopPaths desktopPaths(@Value("${mathproofmesh.desktop.root}") String root) {
    return DesktopPaths.discover(Path.of(root));
  }

  @Bean
  SettingsStore desktopSettingsStore(DesktopPaths paths, ObjectMapper mapper) {
    return new SettingsStore(paths.settingsFile(), mapper);
  }

  @Bean
  CredentialVault desktopCredentialVault(DesktopPaths paths, ObjectMapper mapper) {
    return new CredentialVault(paths.credentialsFile(), mapper);
  }

  @Bean
  DesktopConfigService desktopConfigService(
      DesktopPaths paths, CredentialVault credentials) {
    return new DesktopConfigService(paths, credentials);
  }

  @Bean
  DesktopRuntimeLocator desktopRuntimeLocator() {
    return new DesktopRuntimeLocator();
  }

  @Bean
  DesktopLiveRuntimeFactory desktopLiveRuntimeFactory(
      DesktopConfigService configs, DesktopRuntimeLocator locator) {
    return new DesktopLiveRuntimeFactory(configs, locator);
  }

  @Bean
  DockerSandboxPreflight dockerSandboxPreflight() {
    return new DockerSandboxPreflight();
  }

  @Bean
  DesktopProviderProbe desktopProviderProbe(
      SettingsStore settings, DesktopLiveRuntimeFactory runtimes) {
    return new DesktopProviderProbe(settings, runtimes);
  }

  @Bean
  RunExecutionBackend desktopRunExecutionBackend(
      DesktopPaths paths,
      SettingsStore settings,
      DesktopLiveRuntimeFactory runtimes,
      DesktopRuntimeLocator locator,
      DockerSandboxPreflight dockerPreflight) {
    return new DesktopLiveRunExecutionBackend(
        paths, settings, runtimes, locator, dockerPreflight);
  }

  @Bean
  RunRepository desktopRunRepository(DesktopPaths paths, ObjectMapper mapper) {
    return new RunRepository(paths, mapper);
  }

  @Bean(destroyMethod = "close")
  DesktopRunManager desktopRunManager(
      DesktopPaths paths,
      SettingsStore settings,
      CredentialVault credentials,
      DesktopConfigService configs,
      DesktopProviderProbe providerProbe,
      RunRepository repository,
      RunApiService runs) {
    return new DesktopRunManager(
        paths, settings, credentials, configs, providerProbe, repository, runs);
  }
}
