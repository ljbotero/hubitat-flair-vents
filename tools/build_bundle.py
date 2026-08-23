#!/usr/bin/env python3
"""Build the Hubitat Bundle zip from the current source files.

A Hubitat Bundle is a flat zip containing the app, drivers, and library Groovy
files (renamed to their on-device `bot.flair.<Name>.groovy` identifiers) plus an
`install.txt` / `update.txt` manifest listing them.

Two of the bundle entries differ only by case (`bot.flair.Flairvents.groovy` for
the vent DRIVER vs `bot.flair.FlairVents.groovy` for the APP). macOS is
case-insensitive, so we cannot stage them as separate files on disk; instead we
write each archive entry explicitly via zipfile with an arcname, reading content
straight from the source path. This keeps both case variants distinct.

Usage:
    python3 tools/build_bundle.py [--version X.YYY]

If --version is omitted, the value is read from packageManifest.json's
`betaVersion` (the beta-channel bundle). The output is written to
`bundles/flair-vents.v<version>.zip`.
"""
import argparse
import json
import os
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _strip_comments(text):
    """Remove // line comments and /* */ block comments from Groovy source.

    String-aware: single/double/triple-quoted string literals (and the slashy
    GString form is NOT used in this codebase) are preserved verbatim, including
    any // or /* */ sequences inside them. Returns the comment-stripped text with
    original newlines preserved (blank-line collapsing happens separately and is
    also string-aware via the returned per-line in-string flags).
    """
    out = []
    in_str = None          # one of: "'", '"', "'''", '"""', or None
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        two = text[i:i+2]
        three = text[i:i+3]
        if in_str:
            # Inside a string literal: look only for the matching terminator.
            if c == '\\' and len(in_str) == 1:
                out.append(text[i:i+2]); i += 2; continue
            if len(in_str) == 3 and three == in_str:
                out.append(three); i += 3; in_str = None; continue
            if len(in_str) == 1 and c == in_str:
                out.append(c); i += 1; in_str = None; continue
            out.append(c); i += 1; continue
        # Not in a string.
        if three in ("'''", '"""'):
            out.append(three); i += 3; in_str = three; continue
        if c in ("'", '"'):
            out.append(c); i += 1; in_str = c; continue
        if two == '//':
            # Drop to end of line (keep the newline).
            j = text.find('\n', i)
            if j == -1:
                break
            i = j; continue
        if two == '/*':
            j = text.find('*/', i + 2)
            i = (j + 2) if j != -1 else n
            continue
        out.append(c); i += 1
    return ''.join(out)


def _in_triple_string_flags(text):
    """Return a set of line indices that fall INSIDE a triple-quoted string,
    so blank-line removal never touches blank lines that are part of multi-line
    string literals (e.g. embedded HTML)."""
    inside = set()
    in_triple = None
    i, n = 0, len(text)
    line = 0
    while i < n:
        three = text[i:i+3]
        if in_triple:
            if three == in_triple:
                in_triple = None; i += 3; continue
            if text[i] == '\n':
                inside.add(line); line += 1
            i += 1; continue
        if three in ("'''", '"""'):
            in_triple = three; i += 3; continue
        if text[i] == '\n':
            line += 1
        # skip single-line strings quickly to avoid false triple matches
        if text[i] in ("'", '"'):
            q = text[i]
            i += 1
            while i < n and text[i] not in (q, '\n'):
                if text[i] == '\\':
                    i += 1
                i += 1
            continue
        i += 1
    return inside


def minify_groovy(text):
    """Strip comments and collapse blank lines from Groovy, preserving all code
    and all string-literal contents byte-for-byte. Safe for the Hubitat sandbox."""
    stripped = _strip_comments(text)
    inside = _in_triple_string_flags(stripped)
    kept = []
    for idx, ln in enumerate(stripped.split('\n')):
        if ln.strip() == '' and idx not in inside:
            continue
        kept.append(ln.rstrip() if idx not in inside else ln)
    return '\n'.join(kept) + '\n'


def _assert_minify_safe(original, minified):
    """Self-check: minify must only remove comments/blank lines. After stripping
    comments+blanks from BOTH, the results must be identical, proving no code or
    string-literal content was altered."""
    a = _strip_comments(original)
    b = _strip_comments(minified)
    norm = lambda s: '\n'.join(l.rstrip() for l in s.split('\n') if l.strip() != '')
    if norm(a) != norm(b):
        raise SystemExit('minify safety check FAILED: code content changed')

# Map: bundle archive name -> (manifest kind, source path relative to repo root)
ENTRIES = [
    ("bot.flair.FlairVentsDabv2.groovy",           "library", "libraries/flair-vents-dabv2.groovy"),
    ("bot.flair.Flairvents.groovy",                "driver",  "src/hubitat-flair-vents-driver.groovy"),
    ("bot.flair.Flairpucks.groovy",                "driver",  "src/hubitat-flair-vents-pucks-driver.groovy"),
    ("bot.flair.FlairVentsRoomDiagnostics.groovy", "driver",  "src/hubitat-flair-vents-room-diagnostics-driver.groovy"),
    ("bot.flair.FlairVentsZoneSummary.groovy",     "driver",  "src/hubitat-flair-vents-zone-summary-driver.groovy"),
    ("bot.flair.FlairVents.groovy",                "app",     "src/hubitat-flair-vents-app.groovy"),
]

NAMESPACE = "bot.flair"


def manifest_beta_version():
    with open(os.path.join(REPO_ROOT, "packageManifest.json")) as fh:
        return json.load(fh)["betaVersion"]


def build_manifest_text(version):
    lines = [NAMESPACE, f"flair-vents-{version}"]
    for arcname, kind, _src in ENTRIES:
        lines.append(f"{kind} {arcname}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="bundle version (defaults to manifest betaVersion)")
    parser.add_argument("--minify", action="store_true",
                        help="strip comments + blank lines from the shipped Groovy "
                             "(NOT recommended; size is not the constraint and it makes "
                             "the bundle source differ from the repo files)")
    args = parser.parse_args()

    version = args.version or manifest_beta_version()
    minify = args.minify
    manifest_text = build_manifest_text(version)
    out_path = os.path.join(REPO_ROOT, "bundles", f"flair-vents.v{version}.zip")

    # Validate sources exist before writing anything.
    for _arc, _kind, src in ENTRIES:
        full = os.path.join(REPO_ROOT, src)
        if not os.path.isfile(full):
            raise SystemExit(f"missing source file: {src}")

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for arcname, _kind, src in ENTRIES:
            with open(os.path.join(REPO_ROOT, src), "r") as fh:
                raw = fh.read()
            content = raw
            if minify:
                content = minify_groovy(raw)
                _assert_minify_safe(raw, content)
            zf.writestr(arcname, content.encode("utf-8"))
        zf.writestr("install.txt", manifest_text)
        zf.writestr("update.txt", manifest_text)

    print(f"wrote {out_path}  (minify={'on' if minify else 'off'})")
    with zipfile.ZipFile(out_path) as zf:
        for info in zf.infolist():
            print(f"  {info.file_size:>8}  {info.filename}")


if __name__ == "__main__":
    main()
