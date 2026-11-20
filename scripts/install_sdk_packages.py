from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT / 'work' / 'downloads'
SDK = ROOT / 'tools' / 'android-sdk'
CHUNK = 1024 * 1024


def fetch_part(job):
    name, url, total, index = job
    start = index * CHUNK
    end = min(total - 1, start + CHUNK - 1)
    part = DOWNLOADS / f'{name}-{index:03d}.part'
    if not part.exists() or part.stat().st_size != end - start + 1:
        subprocess.run(['curl.exe', '-f', '-L', '--silent', '--show-error',
                        '--connect-timeout', '15', '--max-time', '100', '--retry', '3',
                        '-r', f'{start}-{end}', '-o', str(part), url], check=True)
    if part.stat().st_size != end - start + 1:
        raise RuntimeError(f'Unexpected size for {part.name}')
    return part


def install():
    repository = ET.parse(ROOT / 'work' / 'repository.xml').getroot()
    destinations = {
        'platforms;android-35': SDK / 'platforms' / 'android-35',
        'build-tools;35.0.0': SDK / 'build-tools' / '35.0.0',
        'platform-tools': SDK / 'platform-tools',
    }
    selected = {}
    for package in repository:
        name = package.attrib.get('path')
        if name not in destinations:
            continue
        channel = package.find('channelRef')
        if channel is not None and channel.attrib.get('ref') != 'channel-0':
            continue
        revision = package.findtext('revision/major')
        if name == 'platforms;android-35' and revision != '2':
            continue
        for archive in package.findall('archives/archive'):
            if archive.findtext('host-os') not in (None, 'windows'):
                continue
            complete = archive.find('complete')
            selected[name] = (complete.findtext('url'), int(complete.findtext('size')), complete.findtext('checksum'))
    jobs = []
    for name, (archive, total, checksum) in selected.items():
        stem = name.replace(';', '-')
        for index in range((total + CHUNK - 1) // CHUNK):
            jobs.append((stem, 'https://dl.google.com/android/repository/' + archive, total, index))
    with ThreadPoolExecutor(max_workers=12) as executor:
        list(executor.map(fetch_part, jobs))
    for name, (filename, total, checksum) in selected.items():
        archive = DOWNLOADS / filename
        with archive.open('wb') as output:
            for index in range((total + CHUNK - 1) // CHUNK):
                output.write((DOWNLOADS / f'{name.replace(";", "-")}-{index:03d}.part').read_bytes())
        with archive.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha1').hexdigest()
        if digest != checksum:
            raise RuntimeError(f'SDK checksum mismatch for {filename}')
        destination = destinations[name]
        if not destination.resolve().is_relative_to(SDK.resolve()):
            raise RuntimeError('Unexpected SDK destination')
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                relative = Path(*Path(entry.filename).parts[1:])
                target = destination / relative
                if not target.resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError('Unexpected archive path')
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(package.read(entry))
        print(f'Installed {name} with verified SHA-1 {digest}', flush=True)


if __name__ == '__main__':
    install()
