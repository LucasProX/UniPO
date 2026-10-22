#!/usr/bin/env python3
"""Query or retry failed delivery intents through the authenticated UniPO API only."""

from __future__ import annotations

import argparse
import getpass
import ipaddress
import json
import os
import re
import sys
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


MAX_RESPONSE_BYTES = 262_144
MAX_ID = 9_223_372_036_854_775_807
STATES = {"PENDING", "SENDING", "DELIVERED", "SUPPRESSED", "FAILED"}
CHANNELS = {"REALTIME", "PUSH"}
TOKEN_ENV = "UNIPO_OPS_ACCESS_TOKEN"


class OperationError(Exception):
    """Contains only fixed messages or validated stable status metadata."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def positive(value: str) -> int:
    if not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > MAX_ID:
        raise argparse.ArgumentTypeError("需要有效的正整数")
    return int(value)


def attempts(value: str) -> int:
    if not re.fullmatch(r"0|[1-9][0-9]{0,9}", value) or int(value) > 2_147_483_647:
        raise argparse.ArgumentTypeError("尝试次数需要非负整数")
    return int(value)


def base_url(value: str) -> tuple[str, bool]:
    if any(ord(ch) < 33 or ord(ch) > 126 for ch in value):
        raise OperationError("API 地址格式不正确")
    try:
        parsed = urlsplit(value)
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        raise OperationError("API 地址格式不正确") from None
    if (parsed.scheme not in {"http", "https"} or not host or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or parsed.path.rstrip("/") not in {"/api", "/api/v1"}):
        raise OperationError("API 地址必须是无凭据、无查询参数的 /api 或 /api/v1 地址")
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = host.lower() == "localhost"
    if not loopback and parsed.scheme != "https":
        raise OperationError("远端 API 必须使用 HTTPS；HTTP 测试服务请通过本机 SSH 隧道访问")
    if port is not None and not 1 <= port <= 65535:
        raise OperationError("API 端口不正确")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", "")), loopback


def credential() -> str:
    token = os.environ.get(TOKEN_ENV, "")
    if not token:
        if not sys.stdin.isatty():
            raise OperationError("未提供管理员凭据；请设置受控环境变量或在交互终端运行")
        token = getpass.getpass("管理员 access token（输入不回显）：")
    if not token or len(token) > 8192 or any(ord(ch) < 33 or ord(ch) > 126 for ch in token):
        raise OperationError("管理员凭据格式不正确")
    return token


def stable_failure(status: int, raw: bytes) -> OperationError:
    suffix = ""
    try:
        payload = json.loads(raw)
        code = payload.get("code") if isinstance(payload, dict) else None
        request_id = payload.get("requestId") if isinstance(payload, dict) else None
        if isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code):
            suffix += " code=" + code
        if isinstance(request_id, str) and re.fullmatch(r"[A-Za-z0-9._-]{1,64}", request_id):
            suffix += " requestId=" + request_id
    except (ValueError, UnicodeError):
        pass
    return OperationError("API 请求失败 HTTP " + str(status) + suffix)


def call(base: str, token: str, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(base + "/admin/message-deliveries" + path, data=payload, method=method,
                      headers={"Authorization": "Bearer " + token, "Accept": "application/json",
                               "Content-Type": "application/json"})
    # Do not forward Authorization across redirects or inherit an unrelated desktop proxy.
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=15) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise OperationError("API 响应超出允许大小")
            if response.status != 200:
                raise stable_failure(response.status, raw)
    except HTTPError as failure:
        raw = failure.read(MAX_RESPONSE_BYTES)
        failure.close()
        raise stable_failure(failure.code, raw) from None
    except (URLError, OSError, TimeoutError):
        raise OperationError("API 连接失败；请核对服务状态、地址或 SSH 隧道后重试") from None
    try:
        envelope = json.loads(raw)
    except (ValueError, UnicodeError):
        raise OperationError("API 未返回有效响应包") from None
    if not isinstance(envelope, dict) or envelope.get("code") != "OK" or "data" not in envelope:
        raise OperationError("API 未返回成功响应包")
    return envelope["data"]


def checked_id(value: Any) -> int:
    if type(value) is not int or not 1 <= value <= MAX_ID:
        raise OperationError("API 投递元数据格式不正确")
    return value


def checked_time(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > 48:
        raise OperationError("API 投递时间格式不正确")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise OperationError("API 投递时间格式不正确") from None
    if parsed.tzinfo is None:
        raise OperationError("API 投递时间缺少时区")
    return value


def delivery(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise OperationError("API 投递元数据格式不正确")
    event_id = value.get("eventId")
    state, channel = value.get("state"), value.get("channel")
    count, error = value.get("attempts"), value.get("lastError")
    if (not isinstance(event_id, str) or not re.fullmatch(r"[0-9a-f]{32}", event_id)
            or not isinstance(state, str) or state not in STATES
            or not isinstance(channel, str) or channel not in CHANNELS
            or type(count) is not int or not 0 <= count <= 2_147_483_647
            or error is not None and (not isinstance(error, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", error))):
        raise OperationError("API 投递元数据格式不正确")
    result = {"id": checked_id(value.get("id")), "eventId": event_id,
              "messageId": checked_id(value.get("messageId")), "channel": channel,
              "state": state, "attempts": count, "lastError": error}
    for name in ("createdAt", "availableAt", "leaseUntil", "completedAt"):
        if name in {"createdAt", "availableAt"} and value.get(name) is None:
            raise OperationError("API 投递时间缺失")
        result[name] = checked_time(value.get(name))
    return result


def page(value: Any) -> dict[str, Any]:
    if (not isinstance(value, dict) or not isinstance(value.get("items"), list)
            or len(value["items"]) > 50 or type(value.get("hasMore")) is not bool
            or type(value.get("limit")) is not int or not 1 <= value["limit"] <= 50):
        raise OperationError("API 分页元数据格式不正确")
    if len(value["items"]) > value["limit"]:
        raise OperationError("API 分页元数据格式不正确")
    cursor = value.get("nextCursor")
    if cursor is not None and (not isinstance(cursor, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", cursor)):
        raise OperationError("API 分页游标格式不正确")
    if value["hasMore"] != (cursor is not None):
        raise OperationError("API 分页状态不一致")
    return {"items": [delivery(item) for item in value["items"]], "nextCursor": cursor,
            "hasMore": value["hasMore"], "limit": value["limit"]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8080/api/v1")
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list", help="查询一页投递元数据")
    listing.add_argument("--state", choices=sorted(STATES), default="FAILED")
    listing.add_argument("--channel", choices=sorted(CHANNELS))
    listing.add_argument("--cursor")
    listing.add_argument("--limit", type=positive, default=20)
    retry = commands.add_parser("retry", help="重新排队一条 FAILED 记录")
    retry.add_argument("id", type=positive)
    retry.add_argument("--expected-attempts", type=attempts, required=True)
    retry.add_argument("--reason", required=True)
    retry.add_argument("--allow-remote-write", action="store_true")
    args = parser.parse_args()
    try:
        base, loopback = base_url(args.base)
        if args.command == "retry" and not loopback and not args.allow_remote_write:
            raise OperationError("远端重试需要显式 --allow-remote-write，并核对 --base 目标")
        if args.command == "list" and args.limit > 50:
            raise OperationError("单页数量不能超过 50")
        if args.command == "retry":
            reason = args.reason.strip()
            if not reason or len(reason) > 200 or not all(ch.isprintable() for ch in reason):
                raise OperationError("重试原因需要一至二百字单行文字；不要填写凭据或消息正文")
        token = credential()
        if args.command == "list":
            query = {name: value for name, value in {"state": args.state, "channel": args.channel,
                                                    "cursor": args.cursor, "limit": args.limit}.items() if value is not None}
            output = page(call(base, token, "GET", "?" + urlencode(query)))
        else:
            output = delivery(call(base, token, "POST", "/" + str(args.id) + "/retry",
                                   {"expectedAttempts": args.expected_attempts, "reason": reason}))
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 0
    except OperationError as failure:
        print(str(failure), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
