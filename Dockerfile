FROM debian:buster-slim
RUN set -eux; \
      apt-get update && \
      apt-get -y install --no-install-recommends libsnappy1v5 libsnappy-java libzstd1 liblz4-1 libbz2-1.0 curl jq && \
      apt-get clean && \
      rm -rf /var/lib/apt/lists/*

RUN GRPC_HEALTH_PROBE_VERSION=v0.3.4 && \
    curl -L -s -k https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-amd64 -o /bin/grpc_health_probe && \
    chmod +x /bin/grpc_health_probe

WORKDIR /app
COPY coralogix-kubernetes-operator-app /app
ENTRYPOINT ["/app/coralogix-kubernetes-operator-app"]
STOPSIGNAL SIGTERM
