"""Archive existing project material without deleting files or changing Android sources."""
from pathlib import Path
import csv
import hashlib
import json
import shutil
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'docs' / 'FILE_MIGRATION_2026-11-18.csv'


def checked(path):
    resolved = path.resolve()
    if not resolved.is_relative_to(ROOT.resolve()):
        raise ValueError(f'Outside the project: {resolved}')
    return resolved


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def organize():
    if MANIFEST.exists():
        raise RuntimeError('Migration already recorded. Review the manifest before running again.')
    moves = []
    def plan(source, destination):
        if source.exists():
            moves.append((checked(source), checked(destination)))

    plan(ROOT / 'PROJECT_PLAN.md', ROOT / 'docs/planning/INITIAL_PROJECT_PLAN.md')
    plan(ROOT / 'work/DESIGN_REFERENCES.md', ROOT / 'docs/architecture/DESIGN_REFERENCES.md')
    plan(ROOT / 'artifacts/VALIDATION.md', ROOT / 'reports/acceptance/history/VALIDATION_HISTORY_TO_0.1.7.md')
    for source in sorted((ROOT / 'artifacts').glob('*.apk')):
        category = 'current' if source.name == 'PhoneAgent-0.1.7-debug.apk' else 'archive'
        plan(source, ROOT / 'artifacts/apk' / category / source.name)
    for source in sorted((ROOT / 'work/device-validation').glob('*')):
        if not source.is_file():
            raise RuntimeError('Unexpected evidence directory; review before moving')
        plan(source, ROOT / 'reports/acceptance/evidence/private/2026-11-18' / source.name)
    for source in sorted((ROOT / 'work').glob('*.py')):
        plan(source, ROOT / 'scripts' / source.name)
    for source in sorted((ROOT / 'work').glob('*.log')):
        plan(source, ROOT / 'reports/builds' / source.name)
    plan(ROOT / 'work/gradle-threads.txt', ROOT / 'reports/builds/diagnostics/gradle-threads.txt')
    for name in ['google-speed.part', 'mirror-speed.part']:
        plan(ROOT / 'work' / name, ROOT / 'work/downloads/probes' / name)

    # Preflight every path and destination before the first move. Never overwrite evidence.
    for source, destination in moves:
        if destination.exists():
            raise FileExistsError(f'Destination already exists: {destination}')
    destinations = [str(destination).casefold() for _, destination in moves]
    if len(set(destinations)) != len(destinations):
        raise RuntimeError('Duplicate migration destination')
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    with MANIFEST.open('w', encoding='utf-8-sig', newline='') as manifest:
        writer = csv.DictWriter(manifest, fieldnames=['old_path', 'new_path', 'bytes', 'sha256', 'result'])
        writer.writeheader()
        for source, destination in moves:
            row = dict(old_path=source.relative_to(ROOT).as_posix(),
                       new_path=destination.relative_to(ROOT).as_posix(),
                       bytes=source.stat().st_size, sha256=digest(source), result='pending')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(destination))
            if digest(destination) != row['sha256']:
                raise RuntimeError(f'Hash mismatch: {destination}')
            row['result'] = 'moved_verified'
            writer.writerow(row)
            manifest.flush()
            rows.append(row)

    release = ROOT / 'reports/acceptance/v0.1.7'
    (release / 'evidence').mkdir(parents=True, exist_ok=True)
    private = ROOT / 'reports/acceptance/evidence/private/2026-11-18'
    for name in ['practice-final-0.1.7.txt', 'practice-final-count-0.1.7.txt',
                 'practice-final-success-0.1.7.png', 'wechat-search-final-0.1.7.txt']:
        shutil.copy2(private / name, release / 'evidence' / name)
    checks = release / 'checks'
    checks.mkdir(parents=True, exist_ok=True)
    for source in sorted((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        shutil.copy2(source, checks / source.name)
    for suffix in ['txt', 'html', 'xml']:
        source = ROOT / f'app/build/reports/lint-results-debug.{suffix}'
        if source.exists():
            shutil.copy2(source, checks / source.name)
    shutil.copy2(ROOT / 'app/build/outputs/apk/debug/output-metadata.json', checks / 'output-metadata.json')
    shutil.copy2(ROOT / 'reports/builds/build-0.1.7.log', checks / 'build-0.1.7.log')
    apk = ROOT / 'artifacts/apk/current/PhoneAgent-0.1.7-debug.apk'
    metadata = {'baseline_version': '0.1.7', 'version_code': 8, 'archived_on': '2026-11-18',
                'apk': apk.relative_to(ROOT).as_posix(), 'bytes': apk.stat().st_size,
                'sha256': digest(apk), 'unit_tests': 32, 'unit_test_failures': 0,
                'lint_errors': 0, 'lint_warnings': 5,
                'note': 'Archived existing verification evidence; this filing operation did not rerun phone scenarios.'}
    (checks / 'BASELINE_MANIFEST.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (checks / 'APK_SHA256.txt').write_text(metadata['sha256'] + '  ' + apk.name + '\n', encoding='utf-8')
    print(json.dumps({'moved_and_hash_verified': len(rows), 'migration_manifest': str(MANIFEST),
                      'baseline_apk_sha256': metadata['sha256']}, ensure_ascii=False))


if __name__ == '__main__':
    organize()
