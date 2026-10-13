FROM public.ecr.aws/docker/library/eclipse-temurin:17-jdk-jammy

ARG http_proxy
ARG https_proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash ca-certificates coreutils curl debianutils findutils git grep \
        hostname iproute2 iptables libc-bin lsof net-tools procps \
        python3 python-is-python3 sudo util-linux \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN git clone "https://github.com/shikhachordia/java-opt.git" /app \
    && git -C /app checkout --detach "62b3822a24f1f7fc5ab6e077f7fa35187b932bf9"

CMD ["/bin/bash"]
