FROM public.ecr.aws/docker/library/eclipse-temurin:17-jdk-jammy

ARG http_proxy
ARG https_proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash binutils ca-certificates coreutils curl diffutils findutils \
        gawk git grep hostname iproute2 lsof net-tools nodejs procps \
        python3 python-is-python3 util-linux \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN git clone "https://github.com/shikhachordia/java-opt.git" /app \
    && git -C /app checkout --detach "1e46ed325e66864c7ee465c7b9dbb9470619dfe4"

# The recorded starting workspace contained an empty public directory.
RUN mkdir -p /app/public

CMD ["/bin/bash"]
