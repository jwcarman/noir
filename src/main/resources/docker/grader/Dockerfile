# noir grader image: JDK 21 and the JUnit console launcher. No Maven, no network use at runtime.
# Built locally by noir (org.jwcarman.noir.docker.Images); every version is pinned.
FROM eclipse-temurin:21-jdk-noble

# Ubuntu images ship user "ubuntu" as uid 1000; noir containers run as 1000:1000 named "noir".
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && groupadd --gid 1000 noir \
 && useradd --uid 1000 --gid 1000 --create-home --home-dir /home/noir --shell /bin/bash noir \
 && mkdir -p /opt/noir/lib /opt/noir/bin

# Everything under /opt/noir is root-owned and not writable by "noir": builder code runs as noir.
ADD --chmod=644 --checksum=sha256:e62b96ac475dbcde8599ea905d088f65d90778f86e259b856a49fa5c4ea256ec https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/6.1.3/junit-platform-console-standalone-6.1.3.jar /opt/noir/lib/junit-platform-console-standalone.jar

COPY --chmod=755 grade.sh /opt/noir/bin/grade.sh
COPY --chmod=644 grader.policy /opt/noir/grader.policy
COPY --chmod=644 ForbiddenScan.java /opt/noir/bin/ForbiddenScan.java
RUN chown -R root:root /opt/noir && chmod -R go-w /opt/noir

ENV HOME=/home/noir
USER noir
WORKDIR /work
