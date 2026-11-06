# noir builder image: JDK 25, Maven 3.10.0, Claude Code, JUnit console launcher, PIT, JaCoCo.
# Built locally by noir (org.jwcarman.noir.docker.Images); every version is pinned.
FROM maven:3.10.0-eclipse-temurin-25-noble

ARG NODE_MAJOR=22
ARG CLAUDE_CODE_VERSION=2.1.318

RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates \
 && curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} \
 && npm cache clean --force \
 && rm -rf /var/lib/apt/lists/*

# Ubuntu images ship user "ubuntu" as uid 1000; noir containers run as 1000:1000 named "noir".
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && groupadd --gid 1000 noir \
 && useradd --uid 1000 --gid 1000 --create-home --home-dir /home/noir --shell /bin/bash noir \
 && mkdir -p /opt/noir/lib /opt/noir/bin \
 && chown -R noir:noir /opt/noir

ENV HOME=/home/noir
USER noir

# Toolchain jars (JUnit console launcher, PIT, JaCoCo agent and CLI) and a warm Maven cache for
# the plugins noir uses.
COPY --chown=noir:noir toolchain/pom.xml /tmp/toolchain/pom.xml
RUN mvn -B -q -f /tmp/toolchain/pom.xml package \
 && rm -rf /tmp/toolchain/target

# A tiny project built once so the cache also holds the default lifecycle plugins, Surefire's
# JUnit provider, JUnit Jupiter and the help plugin seal-check.sh uses. Seal checks read this cache
# through maven.repo.local.tail.
COPY --chown=noir:noir toolchain/warm /tmp/warm
RUN cd /tmp/warm \
 && mvn -B -q verify \
 && mvn -B -q org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
      -Dexpression=maven.compiler.release -DforceStdout >/dev/null \
 && mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies \
      -DincludeScope=test -DoutputDirectory=/tmp/warm/deps \
 && rm -rf /tmp/warm

# noir's scripts and scanners. Builder code runs as "noir", so everything under /opt/noir is
# root-owned and not writable by it.
COPY --chmod=755 seal-check.sh build-prep.sh build-post.sh measure.sh /opt/noir/bin/
COPY --chmod=644 ForbiddenScan.java ClassScan.java /opt/noir/bin/
USER root
RUN chown -R root:root /opt/noir && chmod -R go-w /opt/noir
USER noir

WORKDIR /work
