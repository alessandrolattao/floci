#!/usr/bin/env bash
# Fork-only: never sent to floci-io/floci (see FORK.md). The checks before a pull request: what the
# branch's commits change against upstream/main, for the mistakes a tool can find, then the
# checklist the pull request body answers.
#
#   fork/precheck.sh
#
# Runs in the branch's checkout, where upstream is floci-io/floci; uncommitted changes are not
# checked. A branch cut from upstream/main does not carry the fork's files: run it as
# <clone>/fork/precheck.sh. Checks 1 to 3 make it exit non-zero; checks 4 and 5 and the checklist
# are printed for the pull request body.
set -euo pipefail

readonly UPSTREAM=upstream/main
readonly CHECKSTYLE_RELEASES=https://github.com/checkstyle/checkstyle/releases/download
readonly CLOCK_CALLS='Thread\.sleep|Instant\.now\(\)|System\.currentTimeMillis'
readonly NEW_METHOD='(public|private|protected|static) .*\('
# The method a line declares `private static`: the name before the first parenthesis, with no `=`
# before it, so a field initialised by a call is not taken for a method.
readonly PRIVATE_STATIC_METHOD='private static [^=;(]*[^A-Za-z0-9_$]([A-Za-z_$][A-Za-z0-9_$]*)[[:space:]]*\('
# The declarations check 1 reads, each anchored at the start of the line, after its annotations,
# with the declared name as the last group. A method needs a modifier and a return type, which ends
# in a letter, a digit, `>` or `]` before the blank that precedes the name, so a statement such as
# `synchronized (lockFor(key)) {` is not one; `-` keeps a switch arm's `default -> call(` out.
readonly ANNOTATIONS='^([[:space:]]*@[A-Za-z_$][A-Za-z0-9_$.]*(\([^)]*\))?)*[[:space:]]*'
readonly DECLARED_TYPE="${ANNOTATIONS}((public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)[[:space:]]+)*(class|interface|enum|record|@interface)[[:space:]]+([A-Za-z_\$][A-Za-z0-9_\$]*)"
readonly DECLARED_CONSTANT="${ANNOTATIONS}((public|protected|private)[[:space:]]+)?(static[[:space:]]+final|final[[:space:]]+static)[[:space:]][^=;(-]*[^A-Za-z0-9_\$]([A-Za-z_\$][A-Za-z0-9_\$]*)[[:space:]]*[=;]"
readonly DECLARED_METHOD="${ANNOTATIONS}((public|protected|private|static|final|abstract|synchronized|native|default|strictfp)[[:space:]]+)+[^=;(-]*[]A-Za-z0-9_\$>][[:space:]]+([A-Za-z_\$][A-Za-z0-9_\$]*)[[:space:]]*\("
# The fork's files sit next to this script, whichever branch it checks.
FORK_MD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/FORK.md"
readonly FORK_MD

problems=0
base=
work=
# The lines the branch adds and removes, as diff_lines prints them: in *.java files, and under src.
java_lines=()
src_lines=()

die() {
    echo "precheck: $*" >&2
    exit 1
}

section() {
    printf '\n== %s\n' "$*"
}

# problem <text>: a finding of checks 1 to 3, which makes the run fail.
problem() {
    problems=$((problems + 1))
    printf '  %s\n' "$*"
}

# note <text>: a line for the pull request body, which does not fail the run.
note() {
    printf '  %s\n' "$*"
}

# trimmed <text>: <text> without its leading blanks.
trimmed() {
    printf '%s' "${1#"${1%%[![:space:]]*}"}"
}

# diff_lines <pathspec>...: every line the branch changes, as <sign>:<path>:<line>:<text>. An added
# line has the sign + and its path and number in HEAD; a removed one has - and its path and number
# in the merge base.
diff_lines() {
    git -c core.quotePath=false diff --no-color --no-ext-diff --no-renames \
        --src-prefix=a/ --dst-prefix=b/ -U0 "$base" HEAD -- "$@" |
        awk '
            /^diff --git / { header = 1; next }
            header && /^--- / { old = substr($0, 7); next }
            header && /^\+\+\+ / { new = substr($0, 7); next }
            /^@@ / {
                header = 0
                split(substr($2, 2), from, ","); removed = from[1]
                split(substr($3, 2), to, ","); added = to[1]
                next
            }
            header { next }
            /^\+/ { print "+:" new ":" added++ ":" substr($0, 2); next }
            /^-/ { print "-:" old ":" removed++ ":" substr($0, 2); next }
        '
}

# parse_entry <sign>:<path>:<line>:<text>: sets sign, path, line and text. The text keeps every
# colon it has, a trailing one included, which `IFS=: read` would drop.
parse_entry() {
    local rest
    sign=${1%%:*}
    rest=${1#*:}
    path=${rest%%:*}
    rest=${rest#*:}
    line=${rest%%:*}
    text=${rest#*:}
}

check_conflicts() {
    section "1. Conflicts with $UPSTREAM"
    local out status=0 file
    out=$(git -c core.quotePath=false merge-tree --write-tree --name-only --no-messages "$UPSTREAM" HEAD) ||
        status=$?
    case $status in
        0) note "none" ;;
        1)
            while IFS= read -r file; do
                problem "conflict: $file"
            done < <(tail -n +2 <<<"$out" | awk 'NF && !seen[$0]++')
            ;;
        *) die "git merge-tree could not merge $UPSTREAM and HEAD (status $status)" ;;
    esac
}

# declaration <text>: when <text> declares a type, a static final constant or a method with a
# modifier, sets kind, name and private (yes or no) and succeeds.
declaration() {
    local before
    if [[ $1 =~ $DECLARED_TYPE ]]; then
        kind="type"
    elif [[ $1 =~ $DECLARED_CONSTANT ]]; then
        kind="constant"
    elif [[ $1 =~ $DECLARED_METHOD ]]; then
        kind="method"
    else
        return 1
    fi
    name=${BASH_REMATCH[-1]}
    before=" ${1%%"$name"*} "
    if [[ $before == *[[:space:]]private[[:space:]]* ]]; then
        private=yes
    else
        private=no
    fi
}

# Symbols the branch removes or renames: a name a removed line declares and no added line declares.
# Each is looked for on upstream/main where Java lets it be used: a private member, a private
# nested type included, in its own file; any other type in every file; any other member in its own
# file, its package's files and the files that name its class. Only a use upstream/main added after the merge base is reported: a line the merge
# base's version of the file already has (the branch's own removals among them) is dropped, counted
# per file and text, and a caller the branch leaves in place is in HEAD, where the compile reports it.
check_removed_symbols() {
    section "1. Symbols the branch removes that $UPSTREAM started using after the merge base"
    local entry sign path line text kind name private symbol origin class package key hit found=0
    local -A declared=() symbols=() before=()
    local patterns=() paths=() hits=()
    for entry in "${java_lines[@]}"; do
        parse_entry "$entry"
        declaration "$text" || continue
        if [ "$sign" = + ]; then
            declared[$name]=1
        else
            symbols["$kind:$private:$name:$path"]=1
        fi
    done

    for symbol in "${!symbols[@]}"; do
        kind=${symbol%%:*}
        private=${symbol#*:}
        name=${private#*:}
        origin=${name#*:}
        private=${private%%:*}
        name=${name%%:*}
        [ -z "${declared[$name]:-}" ] || continue
        if [ "$kind" = method ]; then
            patterns=(-E -e "(^|[^A-Za-z0-9_\$])${name//\$/\\\$}[[:space:]]*\(" -e "::${name//\$/\\\$}([^A-Za-z0-9_\$]|\$)")
        else
            patterns=(-w -F -e "$name")
        fi
        if [ "$private" = yes ]; then
            paths=("$origin")
        elif [ "$kind" = type ]; then
            paths=('*.java')
        else
            class=$(basename "$origin" .java)
            package=$(dirname "$origin")
            case $package in
                */java/*) package="**/java/${package#*/java/}" ;;
            esac
            paths=("$origin" ":(glob)$package/*.java")
            while IFS= read -r hit; do
                paths+=("${hit#"$UPSTREAM:"}")
            done < <(git -c core.quotePath=false grep -l -w -F -e "$class" "$UPSTREAM" -- '*.java' || true)
        fi
        hits=()
        before=()
        while IFS= read -r hit; do
            parse_entry "base:${hit#"$base:"}"
            key="$path:$text"
            before[$key]=$((${before[$key]:-0} + 1))
        done < <(git -c core.quotePath=false grep -n -I "${patterns[@]}" "$base" -- "${paths[@]}" || true)
        while IFS= read -r hit; do
            parse_entry "upstream:${hit#"$UPSTREAM:"}"
            key="$path:$text"
            if [ "${before[$key]:-0}" -gt 0 ]; then
                before[$key]=$((${before[$key]} - 1))
            else
                hits+=("$path:$line: $(trimmed "$text")")
            fi
        done < <(git -c core.quotePath=false grep -n -I "${patterns[@]}" "$UPSTREAM" -- "${paths[@]}" || true)
        if [ ${#hits[@]} -gt 0 ]; then
            problem "$kind $name, removed from $origin, is used by lines $UPSTREAM added after the merge base:"
            printf '    %s\n' "${hits[@]}"
            found=1
        fi
    done
    [ "$found" -eq 1 ] || note "none"
}

# checkstyle_jar: the path of the Checkstyle CLI at the version pom.xml pins, downloaded once.
checkstyle_jar() {
    local version cache jar
    version=$(git show HEAD:pom.xml | awk '
        /<artifactId>checkstyle<\/artifactId>/ { found = 1; next }
        found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }
    ')
    [ -n "$version" ] || die "pom.xml pins no com.puppycrawl.tools:checkstyle version"
    cache=${XDG_CACHE_HOME:-$HOME/.cache}/floci-precheck
    jar=$cache/checkstyle-$version-all.jar
    if [ ! -s "$jar" ]; then
        mkdir -p "$cache"
        echo "precheck: downloading the Checkstyle $version CLI into $cache" >&2
        curl -fsSL -o "$jar.part" "$CHECKSTYLE_RELEASES/checkstyle-$version/checkstyle-$version-all.jar" ||
            die "could not download the Checkstyle $version CLI"
        mv "$jar.part" "$jar"
    fi
    printf '%s\n' "$jar"
}

check_checkstyle() {
    section "2. Braces and catch blocks on the lines the branch adds (Checkstyle)"
    local files=() java jar out status=0 errors entry sign path line text violation found=0
    local -A added=()
    mapfile -t files < <(git -c core.quotePath=false diff --name-only --no-renames --diff-filter=d \
        "$base" HEAD -- '*.java')
    if [ ${#files[@]} -eq 0 ]; then
        note "no Java file changed"
        return
    fi
    java=${JAVA_HOME:+$JAVA_HOME/bin/}java
    command -v "$java" >/dev/null || die "no Java at $java: set JAVA_HOME to a JDK, or unset it to use the one on PATH"
    jar=$(checkstyle_jar)
    # Only the variable's name excuses an empty catch: commentFormat (?!) matches no comment, where
    # its default .* would let any comment through.
    cat >"$work/checkstyle.xml" <<'EOF'
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <property name="charset" value="UTF-8"/>
    <module name="TreeWalker">
        <module name="NeedBraces"/>
        <module name="EmptyCatchBlock">
            <property name="exceptionVariableName" value="^(ignored|expected)$"/>
            <property name="commentFormat" value="(?!)"/>
        </module>
        <module name="IllegalCatch"/>
    </module>
</module>
EOF
    # The files as HEAD has them, so the line numbers are the diff's whatever the checkout holds.
    mkdir -p "$work/tree"
    git archive --format=tar HEAD -- "${files[@]}" | tar -x -C "$work/tree"
    out=$(cd "$work/tree" && "$java" -jar "$jar" -c "$work/checkstyle.xml" "${files[@]}" 2>&1) || status=$?
    # The CLI exits with the number of violations it reports; any other status means it did not audit.
    errors=$(grep -c '^\[ERROR\] ' <<<"$out" || true)
    [ "$status" -eq $((errors % 256)) ] || die "Checkstyle did not audit the branch's files:
$out"

    for entry in "${java_lines[@]}"; do
        parse_entry "$entry"
        [ "$sign" != + ] || added["$path:$line"]=1
    done
    while IFS= read -r violation; do
        [[ $violation == "[ERROR] $work/tree/"* ]] ||
            die "Checkstyle reported a file outside $work/tree: $violation"
        violation=${violation#"[ERROR] $work/tree/"}
        parse_entry "checkstyle:$violation"
        if [ -n "${added["$path:$line"]:-}" ]; then
            problem "$violation"
            found=1
        fi
    done < <(grep '^\[ERROR\] ' <<<"$out" || true)
    [ "$found" -eq 1 ] || note "none"
}

check_clock() {
    section "3. Sleeps and wall-clock reads on the lines the branch adds"
    local entry sign path line text found=0
    for entry in "${java_lines[@]}"; do
        parse_entry "$entry"
        if [ "$sign" = + ] && [[ $text =~ $CLOCK_CALLS ]]; then
            problem "$path:$line: $(trimmed "$text")"
            found=1
        fi
    done
    [ "$found" -eq 1 ] || note "none"
}

list_new_methods() {
    section "4. Methods the branch adds: look for each on $UPSTREAM before it stays"
    local entry sign path line text name found=0
    local paths=()
    local -A files_of=()
    for entry in "${src_lines[@]}"; do
        parse_entry "$entry"
        if [ "$sign" = + ] && [[ $text =~ $NEW_METHOD ]]; then
            note "$path:$line: $(trimmed "$text")"
            found=1
        fi
    done
    [ "$found" -eq 1 ] || note "none"

    section "4. private static methods the branch adds in more than one file: one shared method"
    found=0
    for entry in "${src_lines[@]}"; do
        parse_entry "$entry"
        if [ "$sign" = + ] && [[ $text =~ $PRIVATE_STATIC_METHOD ]]; then
            name=${BASH_REMATCH[1]}
            case " ${files_of[$name]:-} " in
                *" $path "*) ;;
                *) files_of[$name]="${files_of[$name]:-} $path" ;;
            esac
        fi
    done
    for name in "${!files_of[@]}"; do
        read -ra paths <<<"${files_of[$name]}"
        if [ ${#paths[@]} -gt 1 ]; then
            note "$name:${files_of[$name]}"
            found=1
        fi
    done
    [ "$found" -eq 1 ] || note "none"
}

list_aws_exceptions() {
    section "5. AwsException the branch adds: tie each to a measured AWS error body"
    local entry sign path line text found=0
    for entry in "${java_lines[@]}"; do
        parse_entry "$entry"
        if [ "$sign" = + ] && [[ $text == *"new AwsException("* ]]; then
            note "$path:$line: $(trimmed "$text")"
            found=1
        fi
    done
    [ "$found" -eq 1 ] || note "none"
}

# print_checklist: the list items of FORK.md's "Before a pull request", the questions no tool answers.
print_checklist() {
    section "The checklist the pull request body answers (FORK.md, Before a pull request)"
    awk '
        /^## / { inside = ($0 == "## Before a pull request"); item = 0; next }
        inside && /^- / { item = 1; print "  " $0; next }
        inside && item && /^  +[^ ]/ { print "  " $0; next }
        { item = 0 }
    ' "$FORK_MD"
}

precheck() {
    cd "$(git rev-parse --show-toplevel)"
    [ -r "$FORK_MD" ] || die "$FORK_MD is missing: run the script from the fork's clone"
    git fetch --quiet upstream main || die "could not fetch main from upstream (floci-io/floci)"
    git rev-parse -q --verify "$UPSTREAM^{commit}" >/dev/null ||
        die "no $UPSTREAM after fetching upstream: the checkout needs floci-io/floci as the remote upstream"
    base=$(git merge-base "$UPSTREAM" HEAD) || die "HEAD shares no history with $UPSTREAM"
    # Its physical path, the one Java prints the audited files under when TMPDIR is a symlink.
    work=$(cd "$(mktemp -d)" && pwd -P)
    trap 'rm -rf "$work"' EXIT
    mapfile -t java_lines < <(diff_lines '*.java')
    mapfile -t src_lines < <(diff_lines src)

    printf 'precheck: %s (%s) against %s (%s), merge base %s\n' \
        "$(git rev-parse --abbrev-ref HEAD)" "$(git rev-parse --short HEAD)" \
        "$UPSTREAM" "$(git rev-parse --short "$UPSTREAM")" "$(git rev-parse --short "$base")"

    check_conflicts
    check_removed_symbols
    check_checkstyle
    check_clock
    list_new_methods
    list_aws_exceptions
    print_checklist

    if [ "$problems" -gt 0 ]; then
        printf '\nprecheck: %d problem(s) in checks 1 to 3\n' "$problems"
        exit 1
    fi
    printf '\nprecheck: checks 1 to 3 found nothing\n'
}

precheck "$@"
