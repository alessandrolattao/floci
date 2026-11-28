# The alessandrolattao/floci fork

This repository is a fork of [floci-io/floci](https://github.com/floci-io/floci). It publishes an
image of Floci that carries our fixes before floci-io/floci merges them, so our projects can run
them today.

Its `main` is floci-io/floci's `main` plus commits that only add the fork's own files. These files
never go to floci-io/floci:

- `FORK.md`, this file
- `fork/precheck.sh`, the checks before a pull request
- `fork/round.sh`, the round
- `fork/suite.sh` and `fork/suite.Dockerfile`, the suite the round runs and its container
- `.github/workflows/integration-image.yml`, the image

## Before a pull request

`fork/precheck.sh` checks what a branch changes against `upstream/main`, for the mistakes a tool
can find, then prints the checklist below, which the pull request body answers. It runs in the
branch's checkout, where `upstream` is floci-io/floci, with `git`, `curl` and Java. A
branch cut from `upstream/main` does not carry the fork's files: run the script from the fork's
clone, `<clone>/fork/precheck.sh`. Its first run downloads the Checkstyle CLI at `pom.xml`'s
version into `${XDG_CACHE_HOME:-$HOME/.cache}/floci-precheck/`.

It fetches `upstream`'s `main` and reads the branch's commits from their merge base with
`upstream/main` (`git diff upstream/main...HEAD`), so a branch behind `upstream/main` is not
charged with upstream's later changes; uncommitted changes are not checked. Checks 2, 3 and 5 read
`*.java` files, check 4 reads `src`. It exits non-zero when it finds:

1. a conflict with `upstream/main`, or a symbol the branch removes or renames that `upstream/main`
   started using after the branch's merge base (a caller the branch leaves in place is in `HEAD`,
   where `./mvnw test-compile` reports it)
2. on a line the branch adds, an `if`, `else`, `for`, `while` or `do` without braces, an empty
   `catch`, with a comment or without, whose variable is neither `ignored` nor `expected`, or a
   `catch` of `Exception`, `RuntimeException`, `Throwable` or `Error`
3. on a line the branch adds, `Thread.sleep`, `Instant.now()` or `System.currentTimeMillis`

It prints, without failing:

4. the methods the branch adds, each to be looked for on `upstream/main` before it stays, and the
   `private static` methods it adds in more than one file
5. every `new AwsException(` it adds, each to be tied to a measured AWS error body

The checklist, which `fork/precheck.sh` prints:

- Check the entry points and the callers of each method the branch changes.
- Give every new path its IAM, account and Region context: enforcement on, a denied identity, a
  non-default account, a resource in another account, every path form in `IamActionRegistry`.
- Say what state is left behind when a step after a create, a lock or a deferral throws or races
  with a delete.
- Point every claim about AWS at a raw measurement line.
- Assert the measured AWS error body for each `AwsException`.
- Register every test resource with `PartitionCleanup` or the class's cleanup, with no catch-all
  around the delete.
- Read the valid CloudFormation forms (`AWS::NoValue`, `Fn::If`, `CommaDelimitedList`) through the
  engine's helpers.
- Pass the CloudFormation ratchets: `CfnSchemaCoverageTest`, `CfnResourceInventoryTest`,
  `CfnProvisionerFixtureTest`, `make cfn-schema-surface-sync`, and the rows in
  `getatt-attribute-gaps.tsv`, `schema-surface.tsv`, `writable-property-gaps.tsv` and
  `drop-policy.tsv`.
- Name the SDK and CLI versions that verified each new action.
- Say why, when the pull request adds no tests.
- Follow every step of `CONTRIBUTING.md`'s "Adding a New AWS Service" for a new service.
- Fill the `-` placeholder description of every action row `make docs-sync` adds.
- Run `make partition-baseline` and commit the smaller baseline when a partition literal goes.

Every fork issue carries the label `review`. Its code review runs `fork/precheck.sh` on the branch
and walks this checklist together with `AGENTS.md`, `CONTRIBUTING.md` and
`.github/pull_request_template.md`.

## Pending fixes

A fix goes to floci-io/floci as a pull request. While it waits there, the same branch is also an
open pull request on this fork, base `main`, with the label `integration`: a pending fix. It is
never merged on the fork. It leaves the integration when its pull request on the fork is closed.

## The round

`fork/round.sh` builds the integration from scratch. It runs in a clone where `origin` is this
fork and `upstream` is floci-io/floci, with `gh` logged in and Docker running:

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
6. runs the whole test suite on the result through `fork/suite.sh` (see [The suite](#the-suite))
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

## The suite

`fork/suite.sh` runs Floci's whole test suite the way `.github/workflows/ci.yml` does, inside a
container like CI's runner, so the tools of the machine that runs it cannot change the result.
The container comes from `fork/suite.Dockerfile`: `eclipse-temurin:25.0.5_8-jdk-resolute`, the
Ubuntu 26.04 and Temurin 25.0.5+8 of CI's `ubuntu-latest` with `setup-java` 25 temurin, plus, from
apt, the tools the tests run from the machine's `PATH`: `curl`, `wget`, `unzip`, `python3`,
`node`, and Docker's CLI from Docker's own apt repository. It sets `CI=true`, as GitHub's runner
does.

The container runs as the caller's user with the Docker socket's group, on the machine's network,
with the machine's `/var/run/docker.sock` and `~/.m2`, and the checkout and the machine's `/tmp` at
their own paths, so a path the tests hand to a sibling container, a JUnit `@TempDir` among them,
names the same directory on the machine. Inside it, the suite:

1. starts from a clean build (`./mvnw clean`), so nothing an earlier build left in `target/` can
   change the result
2. lists the test classes and runs the partition's own tests, as `ci.yml` does, compiles once, and
   checks the compiled test classes against the list
3. runs `ci.yml`'s shard job for each of its `SHARD_TOTAL` shards, one after another: the shard's
   classes from `.github/ci/partition.sh`, its images prefetched in the background by
   `.github/ci/prefetch-images.sh`, then the plain classes four at a time
   (`-Dtest.classes.mode=concurrent`) and the `@QuarkusTest` classes, as
   `.github/ci/split-concurrent.py` splits them

Each shard's logs stay in `target/suite/shard-<n>` and the test reports in
`target/surefire-reports`. The suite fails when a shard fails.

Two tests that CI's runner runs are skipped here: the two of
`ContainerCaBundleDockerIntegrationTest`, which skip themselves when Floci runs in a container, as
`ContainerDetector` tells from the `/.dockerenv` that Docker writes in every container. Upstream's
CI runs them on every pull request.

## The image

Pushing an `int-*` tag, or dispatching it on one, runs `integration-image.yml`, which publishes
`ghcr.io/alessandrolattao/floci:<tag>` for `linux/amd64` and `linux/arm64`. The image is the JVM
image of `docker/Dockerfile.jvm-package`, built from the tagged commit, and it carries:

- `org.opencontainers.image.revision`: the tagged commit
- `org.opencontainers.image.url`: this repository's tree at that commit
- `FLOCI_VERSION`: the image reference, which `GET /_floci/health` reports

A tag already published is never built again: it names one commit, and what a commit builds does
not change. The workflow's runs go one at a time, so a tag's push and a dispatch on the same tag
never publish it twice. A pin on an older round keeps working after later rounds. The package is
public, so a pull needs no login.

## Pinning an image

A project pins one exact tag, never a moving one:

```yaml
services:
  floci:
    image: ghcr.io/alessandrolattao/floci:int-<commit>
```

The tags published are this fork's `int-*` tags (`git ls-remote --tags origin 'int-*'`). Moving to
a newer round means changing the tag.
