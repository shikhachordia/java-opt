FROM public.ecr.aws/docker/library/eclipse-temurin:8-jdk-jammy

ARG http_proxy
ARG https_proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       bash ca-certificates coreutils curl findutils git grep hostname \
       iproute2 net-tools procps python3 python-is-python3 sed tar util-linux xz-utils \
    && rm -rf /var/lib/apt/lists/*

# Match the Node.js version reported by the paired session; npm is included.
RUN case "$(dpkg --print-architecture)" in \
       amd64) node_arch=x64 ;; arm64) node_arch=arm64 ;; *) exit 1 ;; esac \
    && node_archive="node-v24.14.1-linux-${node_arch}.tar.xz" \
    && curl -fsSL "https://nodejs.org/dist/v24.14.1/${node_archive}" -o "/tmp/${node_archive}" \
    && curl -fsSL "https://nodejs.org/dist/v24.14.1/SHASUMS256.txt" -o /tmp/node-shasums \
    && cd /tmp \
    && grep " ${node_archive}$" /tmp/node-shasums | sha256sum -c - \
    && tar -xJf "/tmp/${node_archive}" -C /usr/local --strip-components=1 \
    && rm "/tmp/${node_archive}" /tmp/node-shasums

WORKDIR /app

RUN git clone "https://github.com/shikhachordia/java-opt.git" /app \
    && git -C /app checkout --detach "1e46ed325e66864c7ee465c7b9dbb9470619dfe4"

# Restore files already present when this task started, preserving Git HEAD.
# This snapshot contains no news-link click-count implementation.
RUN git -C /app diff --binary \
       "1e46ed325e66864c7ee465c7b9dbb9470619dfe4" \
       "279f1a9961e16364956e5101370e8d9839058532" \
       > /tmp/opt-pre-session.patch \
    && git -C /app apply /tmp/opt-pre-session.patch \
    && rm /tmp/opt-pre-session.patch

CMD ["/bin/bash"]
