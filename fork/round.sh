#!/usr/bin/env bash
# Fork-only: never sent to floci-io/floci (see FORK.md). One round of the integration:
# floci-io/floci's main, the fork's own files, then every pending fix, merged from scratch, tested,
# tagged int-<short commit> and pushed, which publishes ghcr.io/alessandrolattao/floci:<tag>.
#
#   fork/round.sh
#
# Runs in a clone where origin is alessandrolattao/floci and upstream is floci-io/floci, with gh
# logged in and local-tools/shards.sh in place. A merge conflict or a red suite stops the round
# before integration or the tag is pushed.
set -euo pipefail

readonly FORK=alessandrolattao/floci
readonly LABEL=integration
# The fork's own workflows. Every other workflow on the fork is upstream's, and stays disabled.
readonly OWN_WORKFLOWS=(.github/workflows/integration-image.yml)

die() {
    echo "round: $*" >&2
    exit 1
}

# git_signed <git args>: git with the SSH key as signing key, for the commits, merges and tags the
# round makes, without touching the clone's configuration.
git_signed() {
    git -c gpg.format=ssh -c user.signingkey="$HOME/.ssh/id_ed25519.pub" "$@"
}

# disable_upstream_workflows: disables on the fork every active workflow that is not one of
# OWN_WORKFLOWS, so pushing main runs nothing of upstream's.
disable_upstream_workflows() {
    local listed entry id path own
    listed=$(gh workflow list --repo "$FORK" --all --limit 500 --json id,path,state \
        --jq '.[] | select(.state == "active" and (.path | startswith(".github/workflows/"))) | "\(.id) \(.path)"')
    while read -r id path; do
        [ -n "$id" ] || continue
        for own in "${OWN_WORKFLOWS[@]}"; do
            [ "$path" != "$own" ] || continue 2
        done
        gh workflow disable "$id" --repo "$FORK"
        echo "round: disabled $path"
    done <<<"$listed"
}

# merges_now <ref> <main> <head>...: succeeds when <ref> is <main> with each <head> merged on top
# of it, in order, one merge commit each, as this round would build it.
merges_now() {
    local commit=$1 base=$2 i
    shift 2
    local heads=("$@")
    commit=$(git rev-parse -q --verify "$commit^{commit}") || return 1
    for ((i = ${#heads[@]} - 1; i >= 0; i--)); do
        [ "$(git rev-parse -q --verify "$commit^2" || true)" = "${heads[i]}" ] || return 1
        commit=$(git rev-parse "$commit^1")
    done
    [ "$commit" = "$(git rev-parse "$base")" ]
}

round() {
    cd "$(git rev-parse --show-toplevel)"
    [ -x local-tools/shards.sh ] || die "local-tools/shards.sh is missing: the round cannot run the suite"
    git diff --quiet HEAD -- || die "the checkout has uncommitted changes"

    git fetch upstream
    git fetch origin
    disable_upstream_workflows

    # [skip ci] keeps the push of main from starting any push or pull_request workflow, one that
    # the merge brings included; the second pass disables what that push registered.
    git switch main
    git merge --ff-only origin/main
    if ! git_signed merge -S --no-ff -m "Merge upstream/main into main [skip ci]" upstream/main; then
        git merge --abort
        die "upstream/main conflicts with main: nothing pushed"
    fi
    git push origin main
    disable_upstream_workflows

    local listed entries entry numbers=() heads=() i
    listed=$(gh pr list --repo "$FORK" --base main --label "$LABEL" --state open --limit 100 \
        --json number,headRefOid --jq 'sort_by(.number) | .[] | "\(.number) \(.headRefOid)"')
    mapfile -t entries <<<"$listed"
    for entry in "${entries[@]}"; do
        [ -n "$entry" ] || continue
        numbers+=("${entry% *}")
        heads+=("${entry#* }")
        git fetch origin "refs/pull/${entry% *}/head"
        [ "$(git rev-parse FETCH_HEAD)" = "${entry#* }" ] || die "#${entry% *} moved while the round read it: run it again"
    done

    if merges_now "refs/remotes/origin/$LABEL" main "${heads[@]}"; then
        echo "round: origin/$LABEL already merges main and the pending fixes: nothing new"
        git switch -C "$LABEL" "origin/$LABEL"
    else
        git switch -C "$LABEL" main
        for i in "${!heads[@]}"; do
            if ! git_signed merge -S --no-ff --no-edit -m "Merge #${numbers[i]} into $LABEL" "${heads[i]}"; then
                git merge --abort
                die "#${numbers[i]} conflicts with main or an earlier pending fix: nothing pushed but main"
            fi
            echo "round: merged #${numbers[i]} (${heads[i]})"
        done
    fi

    local commit tag published tagged lease
    commit=$(git rev-parse HEAD)
    tag="int-$(git rev-parse --short HEAD)"
    lease=$(git rev-parse -q --verify "refs/remotes/origin/$LABEL" || true)

    published=$(git ls-remote --tags origin "refs/tags/$tag" "refs/tags/$tag^{}" | awk '{ print $1 }' | tail -1)
    if [ -n "$published" ]; then
        [ "$published" = "$commit" ] || die "$tag on origin names $published, not $commit"
        git push --force-with-lease="refs/heads/$LABEL:$lease" origin "$LABEL"
        echo "round: $tag is already on origin: its suite passed, nothing to publish"
        return 0
    fi

    local-tools/shards.sh "$PWD" || die "the suite failed on $commit: nothing pushed but main"

    tagged=$(git rev-parse -q --verify "refs/tags/$tag^{commit}" || true)
    if [ -z "$tagged" ]; then
        git_signed tag -s -m "Integration round on $commit" "$tag" "$commit"
    elif [ "$tagged" != "$commit" ]; then
        die "$tag already names $tagged, not $commit"
    fi

    git push --force-with-lease="refs/heads/$LABEL:$lease" origin "$LABEL"
    git push origin "refs/tags/$tag"
    echo "round: $tag"
}

# The whole round is one function call on one line, so bash has read all of it before a git
# switch can rewrite this file under it.
round "$@"; exit $?
