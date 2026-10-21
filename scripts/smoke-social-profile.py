#!/usr/bin/env python3
"""Exercise relationships and profile images in the isolated loopback test API only."""
import argparse
import binascii
import http.client
import json
from pathlib import Path
import secrets
import struct
import zlib


def request(path, body=None, token=None, method=None, content_type='application/json'):
    connection = http.client.HTTPConnection('127.0.0.1', 18081, timeout=25)
    headers = {'Content-Type': content_type}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    payload = body if isinstance(body, bytes) else json.dumps(body) if body is not None else None
    connection.request(method or ('POST' if body is not None else 'GET'), '/api/v1/' + path, payload, headers)
    response = connection.getresponse()
    data = response.read()
    status = response.status
    connection.close()
    return status, json.loads(data)


def api(path, body=None, token=None, method=None, content_type='application/json'):
    status, result = request(path, body, token, method, content_type)
    assert status == 200 and result.get('code') == 'OK', (path, status, result.get('code'))
    return result['data']


def account(prefix):
    phone = '155' + format(secrets.randbelow(100_000_000), '08d')
    api('auth/sms-code', {'phone': phone})
    session = api('auth/sms-login', {'phone': phone, 'code': '7391'})
    token = session['token']
    uid = session['user']['publicUid']
    profile = api('users/me/onboarding', {
        'nickname': prefix + uid[-6:], 'signature': '把校园里的小事记录下来。',
        'grade': '大二', 'gender': 'female', 'birthYear': 2002,
        'showAge': False, 'showGender': False, 'showGrade': False,
    }, token, 'PUT')
    assert profile['onboardingCompleted']
    return token, uid


def profile(uid, token=None):
    return api('users/' + uid, token=token)


def follow(uid, token, enabled=True):
    return api('users/' + uid + '/follow', token=token, method='POST' if enabled else 'DELETE')


def cover_png():
    width, height = 600, 360
    rows = []
    for y in range(height):
        pixels = bytearray()
        for x in range(width):
            if (x - 460) ** 2 + (y - 105) ** 2 < 68 ** 2:
                color = (246, 204, 126)
            else:
                color = (45 + x * 100 // width, 108 + y * 90 // height, 235)
            pixels.extend(color)
        rows.append(b'\x00' + pixels)
    def chunk(kind, value):
        return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', binascii.crc32(kind + value) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--viewer-uid', help='Optional existing test viewer to receive a follow from the fixture user')
parser.add_argument('--asset-path', type=Path, help='Optional generated non-private image for simulator picker QA')
args = parser.parse_args()
a, uid_a = account('青禾')
b, uid_b = account('海风')
initial = profile(uid_b, a)
assert not initial['following'] and not initial['followedBy'] and not initial['mine']
follow(uid_b, a)
one_way = profile(uid_b, a)
assert one_way['following'] and not one_way['followedBy']
follow(uid_a, b)
mutual = profile(uid_b, a)
assert mutual['following'] and mutual['followedBy']
follow(uid_b, a, False)
incoming = profile(uid_b, a)
assert not incoming['following'] and incoming['followedBy']
follow(uid_b, a)
assert profile(uid_a, a)['mine']
status, _ = request('users/' + uid_a + '/follow', token=a, method='POST')
assert status == 400
print('PASS not-following / following / mutual / incoming-only / own-profile relationships')

image = cover_png()
if args.asset_path:
    args.asset_path.write_bytes(image)
boundary = 'UniPOSocial' + secrets.token_hex(8)
multipart = (('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="cover.png"\r\n'
              'Content-Type: image/png\r\n\r\n').encode() + image + ('\r\n--' + boundary + '--\r\n').encode())
upload = api('media/upload?purpose=profile-background', multipart, b, 'POST', 'multipart/form-data; boundary=' + boundary)
url = upload['url']
saved = api('users/me', {'backgroundUrl': url}, b, 'PUT')
assert saved['backgroundUrl'] == url
assert api('auth/me', token=b)['backgroundUrl'] == url
public = profile(uid_b)
assert public['profile']['backgroundUrl'] == url
assert public['profile']['email'] is None and public['profile']['goalTags'] is None
assert public['profile']['gender'] is None and public['profile']['birthYear'] is None and public['profile']['grade'] is None
assert profile(uid_b, a)['profile']['backgroundUrl'] == url
api('users/me', {'backgroundUrl': ''}, b, 'PUT')
assert profile(uid_b)['profile']['backgroundUrl'] is None
api('users/me', {'backgroundUrl': url}, b, 'PUT')
print('PASS background upload / persistence / anonymous visibility / privacy / reset')

post = api('posts', {'title': '今天的校园手记', 'content': '把今天发现的小事和学习方法记下来，欢迎一起交流。', 'board': 'school', 'tags': ['校园日常']}, b)
comment = api('posts/' + str(post['id']) + '/comments', {'content': '这份校园记录很有意思，期待更多分享。'}, a)
comments = api('posts/' + str(post['id']) + '/comments')
assert any(item['id'] == comment['id'] and item['author']['uid'] == uid_a for item in comments)
if args.viewer_uid:
    assert profile(args.viewer_uid)['profile']['publicUid'] == args.viewer_uid
    follow(args.viewer_uid, b)
    api('messages/conversations', {'peerUid': args.viewer_uid}, b)
print('PASS post/comment identities use the same public profile UID')
print('PUBLIC_FIXTURE_UID=' + uid_b)
print('PUBLIC_FIXTURE_POST=' + str(post['id']))
print('SOCIAL_PROFILE_FLOW_PASSED')
