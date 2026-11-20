from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
downloads = root / 'work' / 'downloads'
url = 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'
total = 143040480
chunk_size = 4 * 1024 * 1024


def download_part(index):
    start = index * chunk_size
    end = min(total - 1, start + chunk_size - 1)
    part = downloads / f'sdk-{index:03d}.part'
    if not part.exists() or part.stat().st_size != end - start + 1:
        subprocess.run(['curl.exe', '-f', '-L', '--silent', '--show-error',
                        '--connect-timeout', '15', '--max-time', '90', '--retry', '2',
                        '-r', f'{start}-{end}', '-o', str(part), url], check=True)
    if part.stat().st_size != end - start + 1:
        raise RuntimeError(f'Incorrect SDK chunk {index}')
    print(f'SDK chunk {index + 1} ready', flush=True)
    return part


if __name__ == '__main__':
    count = (total + chunk_size - 1) // chunk_size
    with ThreadPoolExecutor(max_workers=8) as executor:
        parts = list(executor.map(download_part, range(count)))
    archive = downloads / 'android-commandline.zip'
    with archive.open('wb') as output:
        for part in parts:
            output.write(part.read_bytes())
    destination = root / 'tools' / 'android-sdk' / 'cmdline-tools'
    with zipfile.ZipFile(archive) as package:
        package.extractall(destination)
    (destination / 'cmdline-tools').rename(destination / 'latest')
    print('Android command-line tools installed', flush=True)
