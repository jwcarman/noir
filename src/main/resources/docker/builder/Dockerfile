# noir builder image: JDK 21, Maven 3.10.0, Claude Code, JUnit console launcher, PIT.
# Built locally by noir (org.jwcarman.noir.docker.Images); every version is pinned.
FROM maven:3.10.0-eclipse-temurin-21-noble

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

# Toolchain jars (JUnit console launcher, PIT) and a warm Maven cache for the plugins noir uses.
COPY --chown=noir:noir toolchain/pom.xml /tmp/toolchain/pom.xml
RUN mvn -B -q -f /tmp/toolchain/pom.xml package \
 && rm -rf /tmp/toolchain/target

COPY --chown=noir:noir --chmod=755 seal-check.sh /opt/noir/bin/seal-check.sh

WORKDIR /work
