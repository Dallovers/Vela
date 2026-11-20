from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT / 'work' / 'downloads'
TOOLS = ROOT / 'tools'
DOWNLOADS.mkdir(parents=True, exist_ok=True)
TOOLS.mkdir(parents=True, exist_ok=True)


def install(name, url, destination, expected_hash=None):
    archive = DOWNLOADS / f'{name}.zip'
    if not archive.exists() or not zipfile.is_zipfile(archive):
        print(f'Downloading {name}', flush=True)
        subprocess.run(['curl.exe', '-f', '-L', '--silent', '--show-error',
                        '--connect-timeout', '20', '--max-time', '300', '--retry', '2',
                        '-o', str(archive), url], check=True)
    digest = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()
    if expected_hash and digest != expected_hash:
        raise RuntimeError(f'{name} SHA-256 mismatch')
    with zipfile.ZipFile(archive) as package:
        package.extractall(destination)
    print(f'Installed {name} SHA-256 {digest}', flush=True)
    return {'name': name, 'url': url, 'sha256': digest}


if __name__ == '__main__':
    jobs = [
        ('jdk21', 'https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip', TOOLS),
        ('gradle-8.11.1', 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip', TOOLS),
        ('android-commandline', 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip', TOOLS / 'android-sdk' / 'cmdline-tools'),
    ]
    with ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(install, *job) for job in jobs]
        metadata = [future.result() for future in futures]
    sdk_tools = TOOLS / 'android-sdk' / 'cmdline-tools'
    unpacked = sdk_tools / 'cmdline-tools'
    latest = sdk_tools / 'latest'
    if unpacked.exists() and not latest.exists():
        unpacked.rename(latest)
    (TOOLS / 'downloads.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
