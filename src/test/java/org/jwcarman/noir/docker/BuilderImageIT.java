package org.jwcarman.noir.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.exec.ProcessCommandRunner;

@Tag("docker")
class BuilderImageIT {
  static final String TAG = "noir-builder:it";
  static final ContainerPort docker = new DockerCli(new ProcessCommandRunner());

  @BeforeAll
  static void build() {
    Images.ensureBuilder(docker, TAG);
  }

  @Test
  void hasPinnedTools() {
    var r =
        docker.run(
            ContainerSpec.of(
                    TAG,
                    "sh",
                    "-c",
                    "java -version 2>&1 | head -1; mvn -v | head -1; claude --version; id -u;"
                        + " echo HOME=$HOME; ls /opt/noir/lib/junit /opt/noir/lib/pitest;"
                        + " test -x /opt/noir/bin/seal-check.sh && echo seal-ok;"
                        + " ls /opt/noir/lib/jacoco;"
                        + " for f in build-prep.sh build-post.sh measure.sh; do"
                        + " test -x /opt/noir/bin/$f && echo $f-ok; done;"
                        + " test -f /opt/noir/bin/ForbiddenScan.java && echo scan-ok;"
                        + " test -f /opt/noir/bin/ClassScan.java && echo classscan-ok;"
                        + " for f in /opt/noir/bin/x /opt/noir/lib/x /opt/noir/bin/measure.sh"
                        + " /opt/noir/lib/jacoco/org.jacoco.cli-nodeps.jar; do"
                        + " touch $f 2>/dev/null && echo writable:$f; done; echo checked-writes")
                .withNetwork(false)
                .withLimits(NoirConfig.defaults().limits()));
    assertThat(r.ok()).as(r.tail(40)).isTrue();
    assertThat(r.stdout())
        .contains("\"21.")
        .contains("Apache Maven 3.10.0")
        .contains("2.1.318")
        .contains("1000")
        .contains("HOME=/home/noir")
        .contains("junit-platform-console-standalone")
        .contains("pitest-command-line")
        .contains("pitest-junit5-plugin")
        .contains("seal-ok")
        .contains("org.jacoco.agent-runtime.jar")
        .contains("org.jacoco.cli-nodeps.jar")
        .contains("build-prep.sh-ok")
        .contains("build-post.sh-ok")
        .contains("measure.sh-ok")
        .contains("scan-ok")
        .contains("classscan-ok")
        .contains("checked-writes")
        .doesNotContain("writable:");
  }

  @Test
  void pitRunsJupiterTests() throws URISyntaxException {
    var fixture = Path.of(getClass().getResource("/fixtures/pit-smoke").toURI());
    var script =
        String.join(
            " && ",
            "javac -d /tmp/classes /fixture/src/com/example/Adder.java",
            "javac -d /tmp/test-classes -cp '/tmp/classes:/opt/noir/lib/junit/*'"
                + " /fixture/test/com/example/AdderTest.java",
            "java -cp '/opt/noir/lib/pitest/*:/opt/noir/lib/junit/*'"
                + " org.pitest.mutationtest.commandline.MutationCoverageReport"
                + " --reportDir /tmp/pit --targetClasses com.example.Adder"
                + " --targetTests com.example.AdderTest --sourceDirs /fixture/src"
                + " --classPath /tmp/classes,/tmp/test-classes,"
                + "/opt/noir/lib/junit/junit-platform-console-standalone.jar"
                + " --outputFormats XML --timestampedReports=false > /tmp/pit.log 2>&1"
                + " || (cat /tmp/pit.log; exit 1)",
            "cat /tmp/pit/mutations.xml");
    var r =
        docker.run(
            ContainerSpec.of(TAG, "sh", "-c", script)
                .withMounts(List.of(Mount.ro(fixture, "/fixture")))
                .withNetwork(false));
    assertThat(r.ok()).as(r.tail(60)).isTrue();
    assertThat(r.stdout()).contains("status='KILLED'");
  }
}
