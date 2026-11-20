"""Read-only audit of documentation links, migration hashes and baseline checks."""
from pathlib import Path
import csv
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    issues = []
    manifest = ROOT / 'docs/FILE_MIGRATION_2026-11-18.csv'
    with manifest.open(encoding='utf-8-sig') as stream:
        migrated = list(csv.DictReader(stream))
    for row in migrated:
        target = (ROOT / row['new_path']).resolve()
        if not target.is_relative_to(ROOT.resolve()) or not target.is_file():
            issues.append(f'Missing migrated file: {row["new_path"]}')
        elif digest(target) != row['sha256']:
            issues.append(f'Migrated content changed: {row["new_path"]}')

    docs = [ROOT / 'README.md']
    for directory in ['docs', 'handoff', 'reports/acceptance']:
        docs.extend(path for path in (ROOT / directory).rglob('*.md') if 'private' not in path.parts and 'checks' not in path.parts)
    for path in docs:
        content = path.read_text(encoding='utf-8-sig')
        if re.search(r'sk-[A-Za-z0-9_-]{12,}', content):
            issues.append(f'Possible credential literal: {path.relative_to(ROOT)}')
        for link in re.findall(r'\]\(([^)]+)\)', content):
            if re.match(r'^[A-Za-z][A-Za-z0-9+.-]*:', link) or link.startswith('#'):
                continue
            target = (path.parent / link.split('#')[0].strip('<>')).resolve()
            if not target.exists():
                issues.append(f'Broken link: {path.relative_to(ROOT)} -> {link}')

    baseline = ROOT / 'reports/acceptance/v0.1.7/checks'
    metadata = json.loads((baseline / 'BASELINE_MANIFEST.json').read_text(encoding='utf-8'))
    apk = ROOT / metadata['apk']
    if not apk.is_file() or digest(apk) != metadata['sha256']:
        issues.append('Baseline APK hash mismatch')
    suites = [ET.parse(path).getroot() for path in baseline.glob('TEST-*.xml')]
    tests = sum(int(suite.attrib['tests']) for suite in suites)
    failures = sum(int(suite.attrib['failures']) + int(suite.attrib['errors']) for suite in suites)
    if tests != 32 or failures:
        issues.append('Archived test baseline is incomplete or failed')
    if len(list((ROOT / 'artifacts/apk/current').glob('*.apk'))) != 1:
        issues.append('Expected exactly one current APK')

    print(json.dumps({'migrated_files': len(migrated), 'hashes_verified': len(migrated) if not any('migrated' in issue.lower() for issue in issues) else None,
                      'markdown_files_checked': len(docs), 'baseline_tests': tests,
                      'baseline_test_failures': failures, 'issues': issues}, ensure_ascii=False, indent=2))
    if issues:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
