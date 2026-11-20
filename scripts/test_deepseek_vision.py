import base64
import json
import struct
import sys
import urllib.error
import urllib.request
import zlib


def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)


key = sys.stdin.readline().strip()
png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('!IIBBBBB', 80, 80, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress((b'\0' + b'\0\0\xff' * 80) * 80))
png += chunk(b'IEND', b'')
payload = {
    'model': 'deepseek-flash',
    'stream': False,
    'messages': [{'role': 'user', 'content': [
        {'type': 'text', 'text': 'Describe the main color of this image in one word.'},
        {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,' + base64.b64encode(png).decode('ascii')}},
    ]}],
}
request = urllib.request.Request('https://api.deepseek.com/chat/completions',
    data=json.dumps(payload).encode('utf-8'),
    headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(request, timeout=90) as response:
        reply = json.load(response)
        print(json.dumps({'http_status': response.status, 'model': reply.get('model'),
                          'reply': reply['choices'][0]['message']['content'][:100]}), flush=True)
except urllib.error.HTTPError as error:
    print(json.dumps({'http_status': error.code}), flush=True)
except Exception:
    print(json.dumps({'connection_error': True}), flush=True)
