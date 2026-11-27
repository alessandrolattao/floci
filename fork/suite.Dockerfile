# Fork-only: never sent to floci-io/floci (see FORK.md). The container fork/suite.sh runs Floci's
# test suite in: the OS and JDK build of CI's runner (ubuntu-latest with setup-java 25 temurin), plus
# the tools the tests run from the PATH of the machine they run on.
FROM eclipse-temurin:25.0.5_8-jdk-resolute

# Docker's CLI comes from Docker's apt repository, set up as "Install using the apt repository" in
# https://docs.docker.com/engine/install/ubuntu/ says; the tests reach the machine's daemon through
# its socket.
RUN <<EOF
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
tee /etc/apt/sources.list.d/docker.sources <<SOURCES
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
SOURCES
apt-get update
apt-get install -y --no-install-recommends docker-ce-cli nodejs python3 unzip wget
rm -rf /var/lib/apt/lists/*
EOF

# fork/suite.sh runs the container as the caller's user, which has no account here, the image's own
# user removed: Java and Maven then take their home from HOME.
RUN userdel --remove ubuntu && install -d -m 1777 /home/suite
ENV HOME=/home/suite

# As GitHub's runner: a test that needs an image pulls it, or fails, instead of skipping.
ENV CI=true
