FROM public.ecr.aws/docker/library/eclipse-temurin:17-jdk-jammy

ARG http_proxy
ARG https_proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash ca-certificates coreutils curl findutils git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN git clone "https://github.com/shikhachordia/java-opt.git" /app \
    && git -C /app checkout --detach "5f55d3f75231de8fe820b7e031e5ea8fe7d11eb3"

CMD ["/bin/bash"]
