#!/usr/bin/env python3
"""Validate a release tag and decide whether it may auto-publish to Maven Central.

Two tiers, because the two failure modes deserve different treatment:

  Shape      A tag that is not `v<major>.<minor>.<patch>` is a typo, not a
             judgement call -- there is no version anyone would confirm. Exit
             non-zero so nothing is deployed anywhere.

  Succession A tag that is well-formed but skips ahead of the previous release
             (`v1.0.2` -> `v1.0.9`, or the classic `v0.10.6` -> `v0.10.61`) may
             still be deliberate. Rather than block it, emit
             auto_publish=false. publish.yml passes that to the Central
             publishing plugin, so the deployment uploads and validates but
             parks in the Central Portal until a human publishes or drops it.

The asymmetry is deliberate: a Maven Central version can never be deleted or
overwritten, so it is the one channel where a wrong number is permanent.

Writes GitHub Actions `name=value` output lines to stdout.

Usage: check-release-tag.py <tag> <tags-file>
"""

import re
import sys

SEMVER = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def parse(tag):
    m = SEMVER.match(tag)
    return tuple(int(g) for g in m.groups()) if m else None


def successors(version):
    """The three versions that may legitimately follow `version`."""
    major, minor, patch = version
    return [(major, minor, patch + 1), (major, minor + 1, 0), (major + 1, 0, 0)]


def fmt(version):
    return "v%d.%d.%d" % version


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2

    tag, tags_file = argv[1], argv[2]

    current = parse(tag)
    if current is None:
        print(
            "::error title=Malformed release tag::"
            "'%s' is not of the form v<major>.<minor>.<patch>. "
            "Nothing has been deployed. Delete this release and its tag, "
            "then cut the correct version." % tag,
            file=sys.stderr,
        )
        return 1

    with open(tags_file) as handle:
        released = {v for v in (parse(t.strip()) for t in handle) if v}
    released.discard(current)

    # The predecessor is the highest release below this one. Releases above it
    # are ignored so that a maintenance line still works: v1.0.13 follows
    # v1.0.12 even once v2.0.0 exists.
    earlier = sorted(v for v in released if v < current)
    previous = earlier[-1] if earlier else None

    expected = successors(previous) if previous else []

    if not released:
        # Genuinely the first release: nothing to be a successor to.
        ok = True
    elif previous is None:
        # Well-formed, but below every existing release -- a backwards release.
        # Not a typo we can rule on, so it goes to the Portal like any other
        # non-successor rather than publishing unattended.
        ok = False
    else:
        ok = current in expected

    # `version` and `VERSION` are both emitted: the two spellings are already in
    # use across the Spice Labs publish workflows (ginger-j/saffron/annatto read
    # `version`, spice-bom/spice-labs-cli read `VERSION`), and emitting both lets
    # this script drop into every repo unchanged.
    out = [
        ("version", tag[1:]),
        ("VERSION", tag[1:]),
        ("successor_ok", "true" if ok else "false"),
        ("auto_publish", "true" if ok else "false"),
        ("previous", fmt(previous) if previous else ""),
        ("expected", ", ".join(fmt(v) for v in expected)),
    ]
    for name, value in out:
        print("%s=%s" % (name, value))

    if not released:
        print(
            "::notice::%s is the first release; no predecessor to check against."
            % tag,
            file=sys.stderr,
        )
    elif previous is None:
        print(
            "::warning title=Release tag goes backwards::"
            "%s is lower than every existing release (earliest is %s). The "
            "Maven Central deployment will wait in the Central Portal for "
            "manual confirmation." % (tag, fmt(min(released))),
            file=sys.stderr,
        )
    elif ok:
        print(
            "::notice::%s is a natural successor to %s." % (tag, fmt(previous)),
            file=sys.stderr,
        )
    else:
        print(
            "::warning title=Release tag is not a natural successor::"
            "%s does not follow %s (expected one of %s). The Maven Central "
            "deployment will wait in the Central Portal for manual confirmation."
            % (tag, fmt(previous), ", ".join(fmt(v) for v in expected)),
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
