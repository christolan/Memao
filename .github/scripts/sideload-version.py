import os
import re
import subprocess
import sys
from pathlib import Path


GRADLE_FILE = "app/build.gradle.kts"

if len(sys.argv) > 1:
    repository = os.environ["GITHUB_REPOSITORY"]
    source = subprocess.check_output(
        [
            "gh", "api", f"repos/{repository}/contents/{GRADLE_FILE}",
            "-H", "Accept: application/vnd.github.raw+json",
            "--method", "GET", "-f", f"ref={sys.argv[1]}",
        ],
        text=True,
    )
else:
    source = Path(GRADLE_FILE).read_text(encoding="utf-8")

name = re.findall(
    r'^[ \t]*versionName[ \t]*=[ \t]*"([0-9A-Za-z][0-9A-Za-z._+-]*)"[ \t]*(?://.*)?$',
    source,
    re.M,
)
code = re.findall(r"^[ \t]*versionCode[ \t]*=[ \t]*([0-9]+)[ \t]*(?://.*)?$", source, re.M)
if len(name) != 1 or len(code) != 1:
    sys.exit(f"Expected one literal versionName and versionCode in {GRADLE_FILE}.")

print(f"{name[0]} ({int(code[0])})")
