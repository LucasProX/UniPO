#!/usr/bin/env python3
"""Exercise SMS mock only through the private test tunnel, without printing credentials."""
import http.client
import json
import secrets


def request(path, body=None, token=None, production=False):
    host, port = ('49.234.21.63', 80) if production else ('127.0.0.1', 18081)
    if production and body is not None:
        raise RuntimeError('Production writes are not allowed by this test')
    connection = http.client.HTTPConnection(host, port, timeout=20)
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    connection.request('POST' if body is not None else 'GET', '/api/v1/' + path,
                       json.dumps(body) if body is not None else None, headers)
    response = connection.getresponse()
    status, payload = response.status, json.loads(response.read())
    connection.close()
    return status, payload


def success(path, body=None, token=None):
    status, payload = request(path, body, token)
    assert status == 200 and payload.get('code') == 'OK', (path, status, payload.get('code'))
    return payload['data']


phone = '155' + format(secrets.randbelow(100_000_000), '08d')
sent = success('auth/sms-code', {'phone': phone})
assert sent['sent'] and sent['expiresInSeconds'] > 0
print('PASS test code request succeeds without real SMS')
session = success('auth/sms-login', {'phone': phone, 'code': '0000'})
assert session['user']['role'] == 'USER' and session['token'] and session['refreshToken']
print('PASS arbitrary four digits create an ordinary test account')
profile = success('auth/me', token=session['token'])
assert profile['id'] == session['user']['id']
print('PASS authenticated profile is readable')
second = success('auth/sms-login', {'phone': phone, 'code': '7391'})
assert second['user']['id'] == profile['id']
print('PASS another four-digit code signs into the same test account')
for invalid in ('123', '12345', 'abcd', ''):
    status, payload = request('auth/sms-login', {'phone': phone, 'code': invalid})
    assert status == 400, ('invalid code', status)
print('PASS non-four-digit codes are rejected')
status, payload = request('auth/sms-code', {'phone': 'not-a-phone'})
assert status == 400
print('PASS malformed phone numbers are rejected')
status, payload = request('auth/me', token=session['token'], production=True)
assert status == 401
print('PASS test credentials cannot authenticate against production')
print('TEST_SMS_FLOW_PASSED')
