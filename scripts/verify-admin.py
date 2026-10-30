#!/usr/bin/env python3
"""Verify real admin authentication and reversible BFF writes without logging credentials."""
from __future__ import annotations

import http.cookiejar
import json
import os
from pathlib import Path
import shlex
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
VALUES: dict[str, str] = {}
for line in (ROOT / '.env').read_text().splitlines():
    if '=' not in line or line.lstrip().startswith('#'):
        continue
    key, raw = line.split('=', 1)
    try:
        pieces = shlex.split(raw, comments=True)
        VALUES[key.strip()] = pieces[0] if pieces else ''
    except ValueError:
        raise SystemExit('Invalid private environment configuration; no values were printed.')
VALUES.update(os.environ)
BASE = VALUES.get('CAMP_ADMIN_BASE_URL', 'http://127.0.0.1:3000').rstrip('/')
API = VALUES.get('CAMP_ADMIN_VERIFY_API_URL', 'http://127.0.0.1:8001/api/v1').rstrip('/')
ORIGIN = urllib.parse.urlunsplit((*urllib.parse.urlsplit(BASE)[:2], '', '', ''))
PASSWORD = VALUES.get('CAMP_ADMIN_PASSWORD', '')
if not PASSWORD:
    raise SystemExit('Admin password is not configured; no credential was printed.')
JAR = http.cookiejar.CookieJar()
CLIENT = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(JAR))
ANONYMOUS = urllib.request.build_opener(urllib.request.ProxyHandler({}))
CHECKS: list[dict] = []


def request(route: str, method: str = 'GET', body=None, *, origin: str | None = ORIGIN, client=CLIENT, upstream=False):
    headers = {'Content-Type': 'application/json'}
    if origin is not None:
        headers['Origin'] = origin
    target = (API if upstream else BASE) + route
    req = urllib.request.Request(target, data=None if body is None else json.dumps(body).encode(), method=method, headers=headers)
    try:
        with client.open(req, timeout=25) as response:
            return response.status, json.load(response), response.headers
    except urllib.error.HTTPError as error:
        return error.code, json.load(error), error.headers


def check(name: str, passed: bool, detail: str = ''):
    if not passed:
        raise AssertionError(f'{name}: {detail or "expectation not met"}')
    CHECKS.append({'name': name, 'status': 'passed', 'detail': detail})
    print('PASS', name, flush=True)


def overview():
    status, data, _ = request('/api/admin/overview')
    check('authenticated overview response', status == 200, f'HTTP {status}')
    return data


def receiving(site):
    value = site['delivery']
    instructions = value.get('instructions', '')
    return {'acceptsParcels': bool(value['acceptsParcels']), 'location': value.get('location', ''), 'instructions': '\n'.join(instructions) if isinstance(instructions, list) else instructions}


def seed_demo_order_if_isolated(data):
    if data['orders']:
        return
    namespace = str(data['database'].get('namespace', ''))
    check('empty-order seeding restricted to isolated QA scope', namespace.endswith('-qa') or namespace.startswith('qa-') or namespace.startswith('camp-room-admin-qa-'), 'A demo order is required; seeding is permitted only in an isolated QA namespace.')
    status, original_cart, _ = request('/cart', client=ANONYMOUS, upstream=True, origin=None)
    check('read isolated QA cart', status == 200)
    status, products, _ = request('/products', client=ANONYMOUS, upstream=True, origin=None)
    check('read isolated QA products', status == 200)
    quantities = {item['productId']: item['quantity'] for item in original_cart['items']}
    product = next((item for item in products['items'] if item['stock'] > quantities.get(item['id'], 0) + 1), None)
    check('demo product available in QA scope', product is not None)
    try:
        for item in original_cart['items']:
            request('/cart/items/' + urllib.parse.quote(item['productId'], safe=''), 'DELETE', client=ANONYMOUS, upstream=True, origin=None)
        status, _, _ = request('/cart/items', 'POST', {'productId': product['id'], 'quantity': 1}, client=ANONYMOUS, upstream=True, origin=None)
        check('prepare isolated demo cart', status == 200)
        payload = {'destination': 'home', 'recipientName': 'Admin QA Demo', 'address': '12 Test Road, Unit 1 (fictional QA address)', 'idempotencyKey': 'admin-qa-' + uuid.uuid4().hex}
        status, quote, _ = request('/checkout/quote', 'POST', {key: value for key, value in payload.items() if key != 'idempotencyKey'}, client=ANONYMOUS, upstream=True, origin=None)
        check('isolated demo delivery quote', status == 200 and quote.get('eligible') is True)
        status, order, _ = request('/orders', 'POST', payload, client=ANONYMOUS, upstream=True, origin=None)
        check('create clearly labelled isolated demo order', status in (200, 201) and order.get('status') == 'demo_confirmed' and order.get('mode') == 'demo')
    finally:
        _, current, _ = request('/cart', client=ANONYMOUS, upstream=True, origin=None)
        for item in current.get('items', []):
            request('/cart/items/' + urllib.parse.quote(item['productId'], safe=''), 'DELETE', client=ANONYMOUS, upstream=True, origin=None)
        for item in original_cart['items']:
            status, _, _ = request('/cart/items', 'POST', {'productId': item['productId'], 'quantity': item['quantity']}, client=ANONYMOUS, upstream=True, origin=None)
            check('restore isolated original cart item', status == 200)


def main():
    status, _, _ = request('/api/admin/overview', client=ANONYMOUS)
    check('unauthenticated admin request rejected', status == 401)
    status, _, _ = request('/api/auth/login', 'POST', {'password': 'deliberately-wrong-qa-password'}, client=ANONYMOUS)
    check('wrong password rejected', status == 401)
    status, login, headers = request('/api/auth/login', 'POST', {'password': PASSWORD})
    check('correct admin password accepted', status == 200 and login.get('authenticated') is True)
    cookie_headers = headers.get_all('Set-Cookie', [])
    check('session cookie is HttpOnly and SameSite Strict', any('httponly' in value.lower() and 'samesite=strict' in value.lower() for value in cookie_headers))
    status, session, _ = request('/api/auth/session')
    check('authenticated session is observable', status == 200 and session.get('authenticated') is True)
    data = overview()
    check('actual Turso backend reported', data.get('database', {}).get('backend') == 'turso')
    check('provider configuration status excludes credentials', isinstance(data.get('providers'), list) and all('configured' in provider for provider in data['providers']))
    check('real campsite list available', bool(data.get('campsites')))
    seed_demo_order_if_isolated(data)
    data = overview()
    site = data['campsites'][0]
    original_receiving = receiving(site)
    path = '/api/admin/campsites/' + urllib.parse.quote(site['id'], safe='') + '/receiving'
    status, _, _ = request(path, 'PATCH', original_receiving, origin='https://cross-origin.invalid')
    check('cross-origin state change rejected', status == 403)
    modified = dict(original_receiving, acceptsParcels=not original_receiving['acceptsParcels'], location='QA receiving point (temporary)', instructions='Temporary QA receiving instructions; restored after verification.')
    changed = False
    try:
        status, _, _ = request(path, 'PATCH', modified)
        check('receiving edit accepted through authenticated BFF', status == 200)
        changed = True
        saved = next(item for item in overview()['campsites'] if item['id'] == site['id'])
        check('receiving edit persists in actual store', receiving(saved) == modified)
    finally:
        if changed:
            status, _, _ = request(path, 'PATCH', original_receiving)
            check('original receiving conditions restored', status == 200)
    restored = next(item for item in overview()['campsites'] if item['id'] == site['id'])
    check('receiving restoration persists', receiving(restored) == original_receiving)
    order = next((item for item in data['orders'] if item.get('mode') == 'demo'), None)
    check('demo order exists for fulfillment QA', order is not None)
    original = {'fulfillmentStatus': (order.get('fulfillment') or {}).get('status', 'pending'), 'adminNote': (order.get('fulfillment') or {}).get('adminNote', '')}
    patch = {'fulfillmentStatus': 'packing' if original['fulfillmentStatus'] != 'packing' else 'ready', 'adminNote': 'Temporary admin QA note; restored after verification.'}
    path = '/api/admin/orders/' + urllib.parse.quote(order['id'], safe='')
    changed = False
    try:
        status, _, _ = request(path, 'PATCH', patch)
        check('fulfillment edit accepted through authenticated BFF', status == 200)
        changed = True
        saved = next(item for item in overview()['orders'] if item['id'] == order['id'])
        check('fulfillment and note persist', (saved.get('fulfillment') or {}).get('status') == patch['fulfillmentStatus'] and (saved.get('fulfillment') or {}).get('adminNote') == patch['adminNote'])
        check('demo payment status remains truthful', saved['status'] == 'demo_confirmed' and saved['mode'] == 'demo')
    finally:
        if changed:
            status, _, _ = request(path, 'PATCH', original)
            check('original fulfillment restored', status == 200)
    saved = next(item for item in overview()['orders'] if item['id'] == order['id'])
    check('fulfillment restoration persists', (saved.get('fulfillment') or {}).get('status') == original['fulfillmentStatus'] and (saved.get('fulfillment') or {}).get('adminNote') == original['adminNote'])
    status, _, _ = request('/api/auth/logout', 'POST', {})
    check('logout accepted', status == 200)
    status, _, _ = request('/api/admin/overview')
    check('admin request rejected after logout', status == 401)
    out = ROOT / 'artifacts/qa'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'admin-integration.json').write_text(json.dumps({'status': 'passed', 'checks': CHECKS, 'credentialsPrinted': False}, indent=2) + '\n')
    print(f'Admin integration passed: {len(CHECKS)} checks; credentials were not printed.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print requests, payloads, cookies, environment values, or credential-bearing URLs.
        print('Admin integration failed:', type(error).__name__, str(error) if isinstance(error, AssertionError) else 'Check the local admin/API readiness and private configuration.', file=sys.stderr)
        raise SystemExit(1)
