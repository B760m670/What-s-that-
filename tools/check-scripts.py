#!/usr/bin/env python3
"""Static checks for the scripts that run inside the container.

`bash -n` only proves a file parses. It says nothing about a function that is
called but never defined — and that is not hypothetical: an edit that replaced
one function silently took four others with it, the file still parsed, CI went
green, and the failure surfaced on a phone as

    main: line 337: wait_for_session: command not found
    main: line 351: WHALE: unbound variable

These scripts cannot be run here — no proot, no Android, no X server — so this
check and the matching runtime guard are what stand between that mistake and
the device.

A script states what it depends on in WT_REQUIRED_FUNCTIONS. This verifies the
list is true; the script itself verifies it again before it does any work, so a
file that somehow reaches a phone broken says so in one line instead of failing
halfway through a launch.
"""

import re
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path("app/src/main/assets/scripts")

DEF_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{", re.M)
REQUIRED_RE = re.compile(
    r'^WT_REQUIRED_FUNCTIONS="([^"]*)"', re.M | re.S
)


def check(path: Path) -> list[str]:
    text = path.read_text()
    problems: list[str] = []

    r = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
    if r.returncode != 0:
        return [f"{path}: does not parse\n{r.stderr.strip()}"]

    defined = set(DEF_RE.findall(text))
    m = REQUIRED_RE.search(text)

    if not m:
        # Only scripts with helpers need the declaration; a straight-line
        # script has nothing to lose.
        if defined:
            problems.append(
                f"{path}: defines {len(defined)} function(s) but has no "
                f'WT_REQUIRED_FUNCTIONS="..." declaration'
            )
        return problems

    required = m.group(1).split()
    for name in required:
        if name not in defined:
            problems.append(
                f"{path}: WT_REQUIRED_FUNCTIONS lists '{name}', which this "
                f"file does not define"
            )

    for name in sorted(defined - set(required)):
        problems.append(
            f"{path}: defines '{name}' but WT_REQUIRED_FUNCTIONS does not "
            f"list it — add it, so deleting it later is caught"
        )

    if "WT_REQUIRED_FUNCTIONS" not in text.split("WT_REQUIRED_FUNCTIONS=", 1)[1]:
        problems.append(
            f"{path}: declares WT_REQUIRED_FUNCTIONS but never checks it at "
            f"runtime"
        )

    return problems


def main() -> int:
    scripts = sorted(SCRIPT_DIR.glob("*.sh"))
    if not scripts:
        print(f"no scripts in {SCRIPT_DIR} — wrong working directory?", file=sys.stderr)
        return 1

    problems = [p for s in scripts for p in check(s)]
    for p in problems:
        print(f"ERROR: {p}", file=sys.stderr)
    print(f"checked {len(scripts)} script(s): {'FAILED' if problems else 'all good'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
