#!/usr/bin/env python3
"""
Writes a GitHub release's notes from every pull request merged since the previous release.

The release used to carry only the title of the one PR whose merge triggered it ("Manual release" for a
dispatched one), so a release that shipped ten PRs named one of them, or none.

Each PR lands in a section by its conventional-commit type: feat -> New, fix/perf -> Fixes (unless
its scope names a test, ci or build), a
dependabot PR -> Dependencies, anything else -> Under the hood. A PR whose body has a `## Release note`
section (any heading level, any case) contributes that text under its title: that is where a change a
user must act on, or a new API with an example, is written by whoever made it. A PR without one is
listed by title alone.

Usage:
  release-notes.py --version 4.27.0 --sha <release commit>
      Asks the GitHub API (gh, with GH_TOKEN and GITHUB_REPOSITORY) for the previous published release,
      the commits between it and <sha>, and each commit's PR.
  release-notes.py --version 4.27.0 --previous-tag v4.26.0 --prs-json prs.json
      Renders from a file instead: [{"number", "title", "body", "author"}], newest first. Used by the
      test, and to preview notes locally.
"""

import argparse
import json
import os
import re
import subprocess
import sys

SECTIONS = [
    ("new", "New"),
    ("fix", "Fixes"),
    ("deps", "Dependencies"),
    ("internal", "Under the hood"),
]

TITLE_TYPE = re.compile(r"^(?P<type>[a-z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<rest>.+)$")
INTERNAL_SCOPE = re.compile(r"\b(test|tests|ci|build)\b")
NOTE_HEADING = re.compile(r"^(?P<hashes>#{1,6})\s*release\s+notes?\s*:?\s*$", re.IGNORECASE)
ANY_HEADING = re.compile(r"^(?P<hashes>#{1,6})\s")
PR_REF = re.compile(r"\(#(\d+)\)\s*$")
SEMVER_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def section_of(pr):
    if pr.get("author", "").startswith("dependabot") or pr.get("author") == "app/dependabot":
        return "deps"
    m = TITLE_TYPE.match(pr["title"])
    kind = m.group("type") if m else ""
    if kind == "feat":
        return "new"
    if kind in ("fix", "perf"):
        # `fix(linux test):` mends a test, not the library; a user has nothing to read there.
        return "internal" if INTERNAL_SCOPE.search(m.group("scope") or "") else "fix"
    return "internal"


def headline(title):
    """`fix(quic): a client migrates…` -> `A client migrates…`; a title with no type prefix is kept."""
    m = TITLE_TYPE.match(title)
    rest = m.group("rest") if m else title
    return rest[:1].upper() + rest[1:]


def release_note(body):
    """The text under a `Release note` heading, up to the next heading of the same or a higher level."""
    lines = (body or "").replace("\r\n", "\n").split("\n")
    out, level, fence = None, 0, False
    for line in lines:
        if out is None:
            m = NOTE_HEADING.match(line.strip())
            if m:
                out, level = [], len(m.group("hashes"))
            continue
        if line.lstrip().startswith("```"):
            fence = not fence
        h = None if fence else ANY_HEADING.match(line)
        if h and len(h.group("hashes")) <= level:
            break
        out.append(line)
    if out is None:
        return ""
    return "\n".join(out).strip()


def is_breaking(pr):
    m = TITLE_TYPE.match(pr["title"])
    return bool(m and m.group("bang"))


def render(version, previous_tag, prs, repo):
    maven = f"https://central.sonatype.com/artifact/com.ditchoom/socket/{version}"
    parts = [f"**Maven Central:** [com.ditchoom:socket:{version}]({maven})", ""]
    if not prs:
        parts += ["No pull requests were merged since " + (previous_tag or "the previous release") + ".", ""]
    breaking = [pr for pr in prs if is_breaking(pr)]
    if breaking:
        parts += ["## ⚠️ Breaking changes", ""]
        parts += [f"- {headline(pr['title'])} (#{pr['number']})" for pr in breaking]
        parts += [""]
    for key, name in SECTIONS:
        group = [pr for pr in prs if section_of(pr) == key]
        if not group:
            continue
        parts += [f"## {name}", ""]
        for pr in group:
            parts.append(f"- **{headline(pr['title'])}** (#{pr['number']})")
            note = release_note(pr.get("body"))
            if note:
                parts += [""] + [("  " + l) if l.strip() else "" for l in note.split("\n")] + [""]
        parts += [""]
    if previous_tag:
        parts.append(f"**Full diff:** https://github.com/{repo}/compare/{previous_tag}...v{version}")
    return "\n".join(parts).rstrip() + "\n"


def semver(tag):
    m = SEMVER_TAG.match(tag)
    return tuple(int(x) for x in m.groups()) if m else None


def previous_release(repo, version):
    """The highest published (non-draft) release tag below [version]."""
    # --paginate runs the filter per page, so ask for one tag per line rather than one array per page.
    tags = gh("api", f"repos/{repo}/releases", "--paginate", "--jq", ".[] | select(.draft | not) | .tag_name").split()
    target = semver("v" + version)
    lower = [t for t in tags if semver(t) and semver(t) < target]
    return max(lower, key=semver) if lower else None


def fetch_prs(repo, previous_tag, sha):
    raw = gh("api", f"repos/{repo}/compare/{previous_tag}...{sha}", "--jq", "[.commits[].commit.message]")
    numbers = []
    for message in json.loads(raw):
        m = PR_REF.search(message.split("\n", 1)[0])
        if m and m.group(1) not in numbers:
            numbers.append(m.group(1))
    prs = []
    for n in reversed(numbers):  # compare lists oldest first; the notes list newest first
        pr = json.loads(gh("pr", "view", n, "--repo", repo, "--json", "number,title,body,author"))
        pr["author"] = (pr.get("author") or {}).get("login", "")
        prs.append(pr)
    return prs


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--version", required=True)
    p.add_argument("--sha")
    p.add_argument("--previous-tag")
    p.add_argument("--prs-json")
    a = p.parse_args()
    repo = os.environ.get("GITHUB_REPOSITORY", "DitchOoM/socket")
    if a.prs_json:
        with open(a.prs_json) as f:
            prs = json.load(f)
        previous = a.previous_tag
    else:
        if not a.sha:
            p.error("--sha is required unless --prs-json is given")
        previous = a.previous_tag or previous_release(repo, a.version)
        prs = fetch_prs(repo, previous, a.sha) if previous else []
    sys.stdout.write(render(a.version, previous, prs, repo))


if __name__ == "__main__":
    main()
