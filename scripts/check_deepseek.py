import json
import sys
import urllib.error
import urllib.request

key = sys.stdin.readline().strip()
path = sys.argv[1] if len(sys.argv) > 1 else '/models'
assert path in ('/models', '/v1/models')
request = urllib.request.Request(
    'https://api.deepseek.com' + path,
    headers={'Authorization': 'Bearer ' + key},
)
try:
    with urllib.request.urlopen(request, timeout=25) as response:
        payload = json.load(response)
        print(json.dumps({'http_status': response.status,
                          'model_ids': [model['id'] for model in payload.get('data', [])]}, ensure_ascii=True), flush=True)
except urllib.error.HTTPError as error:
    try:
        detail = json.load(error).get('error', {})
        print(json.dumps({'http_status': error.code, 'error_type': detail.get('type'),
                          'error_code': detail.get('code')}), flush=True)
    except Exception:
        print(json.dumps({'http_status': error.code}), flush=True)
except Exception:
    print(json.dumps({'connection_error': True}), flush=True)
