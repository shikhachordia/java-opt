FROM public.ecr.aws/docker/library/eclipse-temurin:17-jdk-jammy

ARG http_proxy
ARG https_proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash ca-certificates coreutils curl findutils git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN git clone "https://github.com/shikhachordia/java-opt.git" /app \
    && git -C /app checkout --detach "1e46ed325e66864c7ee465c7b9dbb9470619dfe4"

# Restore the uncommitted API files present before this planning session.
# The recovery commit supplies file contents; HEAD stays at the recorded base.
RUN git -C /app diff --binary \
        "1e46ed325e66864c7ee465c7b9dbb9470619dfe4" \
        "5f55d3f75231de8fe820b7e031e5ea8fe7d11eb3" \
        > /tmp/opt-pre-session.patch \
    && git -C /app apply /tmp/opt-pre-session.patch \
    && rm /tmp/opt-pre-session.patch

CMD ["/bin/bash"]
