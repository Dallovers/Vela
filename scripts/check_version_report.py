"""Check that a version bump ships its own acceptance report."""
import re
import subprocess
import sys

VERSION_FILE = "app/build.gradle.kts"
VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)
VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([0-9A-Za-z][0-9A-Za-z.+-]*)"\s*$', re.MULTILINE)


def read_file(revision: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{revision}:{path}"], capture_output=True, check=False
    )
    return result.stdout.decode("utf-8-sig") if result.returncode == 0 else None


def version(revision: str) -> tuple[int, str]:
    build_file = read_file(revision, VERSION_FILE)
    if build_file is None:
        raise ValueError(f"Cannot read {VERSION_FILE} at {revision}. Fetch both revisions.")
    names, codes = VERSION_NAME.findall(build_file), VERSION_CODE.findall(build_file)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError(f"Cannot identify a single versionName and versionCode in {VERSION_FILE}.")
    return int(codes[0]), names[0]


def check(base: str, head: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Pass full Git commit hashes.")
    current = version(head)
    first_push = base == "0" * 40
    if not first_push and version(base) == current:
        print("Application version is unchanged.")
        return
    code, name = current
    report = f"reports/acceptance/v{name}/验收报告.md"
    text = read_file(head, report)
    if not text or name not in text or len(text.strip()) < 80:
        raise ValueError(f"Version {name} ({code}) needs a completed report at {report}.")
    if not first_push and text == read_file(base, report):
        raise ValueError(f"The version changed. Update {report} with this build's acceptance results.")
    print(f"Version {name} ({code}) has an updated report at {report}.")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: check_version_report.py BASE_SHA HEAD_SHA")
        check(sys.argv[1], sys.argv[2])
    except ValueError as error:
        raise SystemExit(str(error))
