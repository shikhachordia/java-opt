FROM public.ecr.aws/docker/library/eclipse-temurin:8-jdk-jammy

ARG http_proxy
ARG https_proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       acct bash ca-certificates coreutils curl findutils gawk git grep \
       hostname iproute2 iptables libc-bin net-tools nodejs npm procps \
       python3 python-is-python3 python3-pip sed systemd util-linux \
    && rm -rf /var/lib/apt/lists/*

# Python Playwright was probed before any installation in the paired session.
# Only the Python module is needed; no recorded browser launch requires a browser.
RUN python3 -m pip install --no-cache-dir playwright

WORKDIR /app

RUN git clone "https://github.com/shikhachordia/java-opt.git" /app \
    && git -C /app checkout --detach "1e46ed325e66864c7ee465c7b9dbb9470619dfe4"

# Recover the frontend already present before this task, preserving Git HEAD.
# The recovery snapshot contains no LeetCode, profiling, or telemetry solution.
RUN git -C /app diff --binary \
       "1e46ed325e66864c7ee465c7b9dbb9470619dfe4" \
       "db0d9c83be75443f596acebf8adb382abd50c74e" \
       > /tmp/opt-pre-session.patch \
    && git -C /app apply /tmp/opt-pre-session.patch \
    && rm /tmp/opt-pre-session.patch

CMD ["/bin/bash"]
