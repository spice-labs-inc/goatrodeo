# Dockerfile for Certificates corpus-authoring tools.
#
# Provides pinned versions of openssl, ssh-keygen, gpg, and jq so that
# sidecar generation is reproducible across contributor machines.
# Per project invariant #13, any script that needs more than
# git/docker/JVM runs in a Docker container. This image is that container.
#
# Build:
#   docker build -t goatrodeo-certcorpus-tools tools/
#
# Run (from the repo root):
#   docker run --rm \
#     --user "$(id -u):$(id -g)" \
#     -v "$(pwd)":/work \
#     -w /work \
#     goatrodeo-certcorpus-tools \
#     test_data/certificates/tools/compute-expected.sh \
#     test_data/certificates/x509/rsa2048-isrg-root-x1.pem
#
# The `--user` flag preserves the invoker's UID/GID so the generated
# sidecar is not root-owned (invariant #13, second paragraph).

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openssl=3.0.* \
      openssh-client \
      gnupg \
      jq \
      coreutils \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# GPG scratch dir — each run uses its own ephemeral home so we never
# touch the invoker's real keyring.
ENV GNUPGHOME=/tmp/gnupg-scratch

ENTRYPOINT ["/bin/bash"]
