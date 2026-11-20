from concurrent.futures import ThreadPoolExecutor
import hashlib
from install_sdk_packages import CHUNK, DOWNLOADS, fetch_part

size = 51772507
checksum = 'b08088a70ed35bf03770e29c934b08faef5b5c9b'
url = 'https://dl.google.com/dl/android/maven2/com/android/tools/external/com-intellij/kotlin-compiler/31.9.2/kotlin-compiler-31.9.2.jar'
count = (size + CHUNK - 1) // CHUNK
jobs = [('lint-kotlin', url, size, index) for index in range(count)]
with ThreadPoolExecutor(max_workers=12) as executor:
    parts = list(executor.map(fetch_part, jobs))
archive = DOWNLOADS / 'kotlin-compiler-31.9.2.jar'
with archive.open('wb') as output:
    for part in parts:
        output.write(part.read_bytes())
with archive.open('rb') as source:
    actual = hashlib.file_digest(source, 'sha1').hexdigest()
if actual != checksum:
    raise RuntimeError('Lint compiler checksum mismatch')
print(f'Lint compiler verified {actual}', flush=True)
