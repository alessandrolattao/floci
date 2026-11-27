# The alessandrolattao/floci fork

This repository is a fork of [floci-io/floci](https://github.com/floci-io/floci). It publishes an
image of Floci that carries our fixes before floci-io/floci merges them, so our projects can run
them today.

Its `main` is floci-io/floci's `main` plus commits that only add the fork's own files. These files
never go to floci-io/floci:

- `FORK.md`, this file
- `fork/round.sh`, the round
- `.github/workflows/integration-image.yml`, the image

## Pending fixes

A fix goes to floci-io/floci as a pull request. While it waits there, the same branch is also an
open pull request on this fork, base `main`, with the label `integration`: a pending fix. It is
never merged on the fork. It leaves the integration when its pull request on the fork is closed.

## The round

`fork/round.sh` builds the integration from scratch. It runs in a clone where `origin` is this
fork and `upstream` is floci-io/floci, with `gh` logged in:

1. fetches `upstream` and `origin`
2. disables on the fork every workflow that is not the fork's own (today only
   `integration-image.yml`), so a push of `main` runs nothing of floci-io/floci's
3. merges `upstream/main` into `main` with `[skip ci]` in the merge's message, so the push starts
   no `push` workflow, not even one the merge brings, pushes `main`, then disables again what that
   push brought
4. recreates the branch `integration` from `main`: it never merges `main` into an earlier
   integration
5. merges, in pull request number order, the head of every open pull request labelled
   `integration`, one merge commit each. A head that `main` or a lower-numbered pending fix
   already contains has nothing to merge, and the round leaves it out
6. runs the whole test suite on the result, in CI's four shards, through `local-tools/shards.sh`
   (a script of the clone, outside the repository)
7. tags the result `int-<commit>`, the commit's hash cut to 12 characters, pushes `integration`
   with a lease and pushes the tag. When the tagged commit is the round's `[skip ci]` merge (no
   pull request is pending), the tag's push starts nothing either, so the round starts the image
   with `gh workflow run integration-image.yml --ref <tag>`

A merge conflict or a red suite stops the round before `integration` or the tag is pushed.
Commits, merges and tags are signed with the SSH key at `~/.ssh/id_ed25519.pub`. The suite binds
port 4566, so nothing else may listen there while a round runs.

A round with nothing new publishes nothing. When `origin/integration` already merges `main`'s
head and the same pull request heads, in the same order, the round keeps it instead of building
it again. When the result's tag is already on `origin`, its suite has already passed: the round
leaves `integration` on that commit and stops before the suite. If the tag's image is not on
`ghcr.io` (its run failed, or it never started), the round also starts the image workflow on
the tag with a dispatch.

## The image

Pushing an `int-*` tag, or dispatching it on one, runs `integration-image.yml`, which publishes
`ghcr.io/alessandrolattao/floci:<tag>` for `linux/amd64` and `linux/arm64`. The image is the JVM
image of `docker/Dockerfile.jvm-package`, built from the tagged commit, and it carries:

- `org.opencontainers.image.revision`: the tagged commit
- `org.opencontainers.image.url`: this repository's tree at that commit
- `FLOCI_VERSION`: the image reference, which `GET /_floci/health` reports

A tag already published is never built again: it names one commit, and what a commit builds does
not change. A pin on an older round keeps working after later rounds. The package is public, so a
pull needs no login.

## Pinning an image

A project pins one exact tag, never a moving one:

```yaml
services:
  floci:
    image: ghcr.io/alessandrolattao/floci:int-<commit>
```

The tags published are this fork's `int-*` tags (`git ls-remote --tags origin 'int-*'`). Moving to
a newer round means changing the tag.
