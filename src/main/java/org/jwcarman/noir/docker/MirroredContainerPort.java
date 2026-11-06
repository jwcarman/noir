package org.jwcarman.noir.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

/**
 * Sends Maven Central traffic of builder-image containers to a mirror: mounts a Maven settings file
 * read-only and points Maven at it through {@code MAVEN_ARGS}. Containers of other images are run
 * unchanged.
 */
public final class MirroredContainerPort implements ContainerPort {
  /** Where the settings file appears inside the container. */
  public static final String SETTINGS_PATH = "/opt/noir/maven-settings.xml";

  private final ContainerPort delegate;
  private final String builderImage;
  private final Path settingsFile;

  public MirroredContainerPort(ContainerPort delegate, String builderImage, Path settingsFile) {
    this.delegate = delegate;
    this.builderImage = builderImage;
    this.settingsFile = settingsFile;
  }

  /**
   * {@code delegate} itself when there is no mirror; otherwise a mirrored port whose settings file
   * is written to {@code dir}.
   */
  public static ContainerPort wrap(
      ContainerPort delegate, String builderImage, Optional<String> mirror, Path dir) {
    if (mirror.isEmpty()) {
      return delegate;
    }
    var file = dir.resolve("maven-settings.xml");
    try {
      Files.createDirectories(dir);
      Files.writeString(file, settingsXml(mirror.get()));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + file, e);
    }
    return new MirroredContainerPort(delegate, builderImage, file);
  }

  static String settingsXml(String url) {
    var escaped =
        url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    return """
    <settings>
      <mirrors>
        <mirror>
          <id>noir-mirror</id>
          <mirrorOf>central</mirrorOf>
          <url>%s</url>
        </mirror>
      </mirrors>
    </settings>
    """
        .formatted(escaped);
  }

  @Override
  public ContainerResult run(ContainerSpec spec) {
    if (!builderImage.equals(spec.image())) {
      return delegate.run(spec);
    }
    var mounts = new ArrayList<>(spec.mounts());
    mounts.add(Mount.ro(settingsFile, SETTINGS_PATH));
    var env = new HashMap<>(spec.env());
    env.put("MAVEN_ARGS", "-s " + SETTINGS_PATH);
    return delegate.run(spec.withMounts(mounts).withEnv(env));
  }

  @Override
  public Optional<String> imageId(String image) {
    return delegate.imageId(image);
  }

  @Override
  public void build(Path contextDir, String tag) {
    delegate.build(contextDir, tag);
  }

  @Override
  public int removeLabelled(String label) {
    return delegate.removeLabelled(label);
  }
}
