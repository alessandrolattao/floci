#!/usr/bin/env bash
# Fork-only: never sent to floci-io/floci (see FORK.md). Runs Floci's whole test suite the way
# .github/workflows/ci.yml does, inside a container like CI's runner built from
# fork/suite.Dockerfile, so the tools of the machine it runs on cannot change the result.
#
#   fork/suite.sh
#
# Runs in a checkout of the fork, on a machine with Docker. It runs the shards of ci.yml one after
# another, leaves their logs in target/suite, and fails when one fails.
set -euo pipefail

readonly IMAGE=floci-fork-suite
readonly SOCKET=/var/run/docker.sock
# The image's HOME, where the machine's ~/.m2 is mounted.
readonly CONTAINER_HOME=/home/suite
readonly LOGS=target/suite

die() {
    echo "suite: $*" >&2
    exit 1
}

# container: builds the image and runs the shards in it, as the caller's user with the Docker
# socket's group, on the machine's network, with the checkout at its own path: a path the tests
# hand to a sibling container names the same directory on the machine.
container() {
    local root
    root=$(git rev-parse --show-toplevel)
    docker build --quiet --tag "$IMAGE" - <"$root/fork/suite.Dockerfile" >/dev/null
    mkdir -p "$HOME/.m2"
    docker run --rm --init --network host \
        --user "$(id -u):$(id -g)" --group-add "$(stat -c %g "$SOCKET")" \
        --volume "$SOCKET:$SOCKET" \
        --volume "$root:$root" --workdir "$root" \
        --volume "$HOME/.m2:$CONTAINER_HOME/.m2" \
        "$IMAGE" fork/suite.sh shards
}

# shard_total: ci.yml's SHARD_TOTAL, the number of shards its matrix runs.
shard_total() {
    local total
    total=$(sed -nE 's/^[[:space:]]+SHARD_TOTAL:[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' .github/workflows/ci.yml)
    [[ $total =~ ^[1-9][0-9]*$ ]] || die "no single SHARD_TOTAL in .github/workflows/ci.yml"
    echo "$total"
}

# test_classes <dir> <extension>: the test classes under <dir>, named as ci.yml names them.
test_classes() {
    (cd "$1" && find . \( -name "*Test.$2" -o -name "Test*.$2" -o -name "*Tests.$2" -o -name "*TestCase.$2" \) \
        ! -name '*$*' | sed "s|^\./||; s|\.$2\$||" | LC_ALL=C sort)
}

# summary <log>: the last test count Maven printed in <log>, if it printed one.
summary() {
    grep -a -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$1" | tail -1 || true
}

# shards: ci.yml's shard job for each shard, one after another. Inside the container only.
shards() {
    [ -e /.dockerenv ] || die "the shards run only inside the suite's container: run fork/suite.sh"
    local total shard dir plain quarkus status=0
    total=$(shard_total)

    mkdir -p "$LOGS"
    test_classes src/test/java java >"$LOGS/all-classes.txt"
    python3 -m unittest discover -s .github/ci -p test_partition.py >"$LOGS/test-partition.log" 2>&1 ||
        die "the partition's own tests failed: see $LOGS/test-partition.log"
    ./mvnw test-compile -B >"$LOGS/compile.log" 2>&1 || die "test-compile failed: see $LOGS/compile.log"
    test_classes target/test-classes class >"$LOGS/compiled-classes.txt"
    if ! cmp -s "$LOGS/all-classes.txt" "$LOGS/compiled-classes.txt"; then
        diff "$LOGS/all-classes.txt" "$LOGS/compiled-classes.txt" | head -50 >&2
        die "the test classes in src/test/java differ from those in target/test-classes"
    fi

    for ((shard = 1; shard <= total; shard++)); do
        dir=$LOGS/shard-$shard
        mkdir -p "$dir"
        .github/ci/partition.sh "$LOGS/all-classes.txt" "$total" "$shard" >"$dir/shard.txt"
        # An empty includesFile makes surefire run the whole suite.
        [ -s "$dir/shard.txt" ] || die "shard $shard/$total has no classes"
        grep -v 'NightlyIntegrationTest$' "$dir/shard.txt" >"$dir/prefetch.txt" || true
        .github/ci/prefetch-images.sh "$dir/prefetch.txt" || true
        python3 .github/ci/split-concurrent.py "$dir/shard.txt" "$dir/quarkus.txt" "$dir/plain.txt"

        plain=0
        quarkus=0
        if [ -s "$dir/plain.txt" ]; then
            ./mvnw surefire:test -B \
                -Dsurefire.includesFile="$PWD/$dir/plain.txt" \
                -Dsurefire.failIfNoTests=true \
                -Dsurefire.timeout=300 \
                -Dtest.classes.mode=concurrent >"$dir/plain.log" 2>&1 || plain=$?
        fi
        if [ -s "$dir/quarkus.txt" ]; then
            ./mvnw surefire:test -B \
                -Dsurefire.includesFile="$PWD/$dir/quarkus.txt" \
                -Dsurefire.failIfNoTests=true \
                -Dsurefire.timeout=1500 >"$dir/quarkus.log" 2>&1 || quarkus=$?
        fi
        if [ "$plain" -ne 0 ] || [ "$quarkus" -ne 0 ]; then
            status=1
        fi
        echo "suite: shard $shard/$total: plain exit=$plain $(summary "$dir/plain.log" 2>/dev/null)," \
            "quarkus exit=$quarkus $(summary "$dir/quarkus.log" 2>/dev/null)"
        cat "$dir"/plain.log "$dir"/quarkus.log 2>/dev/null | grep -a -E '<<< (FAIL|ERROR)' | head -20 || true
    done
    return "$status"
}

case "${1:-}" in
    "") container ;;
    shards) shards ;;
    *) die "usage: fork/suite.sh" ;;
esac
