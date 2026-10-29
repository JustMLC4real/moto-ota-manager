"""Query public OSV advisories for the exact resolved release Maven inventory.

Only public group:name:version coordinates are sent; no code, paths, credentials,
device identifiers or firmware metadata. A failed query is UNKNOWN, never clean.
"""
import argparse
import datetime
import json
import urllib.error
import urllib.request
from pathlib import Path


def audit(inventory):
    value = json.loads(inventory.read_text(encoding='utf-8'))
    if value.get('schema') != 1 or value.get('configuration') != 'releaseRuntimeClasspath':
        raise ValueError('Expected resolved release-runtime inventory')
    modules = value['modules']
    if not 1 <= len(modules) <= 1000:
        raise ValueError('Invalid dependency inventory size')
    queries = [{'package': {'ecosystem': 'Maven', 'name': item['group'] + ':' + item['name']}, 'version': item['version']} for item in modules]
    payload = json.dumps({'queries': queries}).encode('utf-8')
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=payload,
                                    headers={'Content-Type': 'application/json', 'User-Agent': 'Moto-OTA-Manager-release-audit'})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('Advisory response oversized')
    results = json.loads(data)['results']
    if len(results) != len(modules):
        raise ValueError('Incomplete advisory response')
    findings = []
    for module, result in zip(modules, results):
        advisories = sorted({item['id'] for item in result.get('vulns', [])})
        if advisories:
            findings.append({'coordinate': ':'.join(module[key] for key in ('group', 'name', 'version')), 'advisories': advisories})
    return {'status': 'findings-require-review' if findings else 'no-matching-advisories-returned',
            'queriedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'source': 'https://api.osv.dev/v1/querybatch',
            'moduleCount': len(modules), 'findings': findings,
            'limits': ['Advisory coverage is incomplete', 'Not a license audit', 'No physical-device or OTA safety claim']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.inventory)
        exit_code = 1 if result['findings'] else 0
    except (OSError, ValueError, KeyError, TypeError, urllib.error.URLError):
        result = {'status': 'unknown-query-failed', 'findings': [], 'clean': False}
        exit_code = 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': result['status'], 'findings': len(result['findings'])}))
    raise SystemExit(exit_code)
