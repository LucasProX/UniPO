#!/usr/bin/env python3
"""DP02 identity acceptance against two disposable loopback fixture instances.

The wrapper owns service startup, databases, secrets, and cleanup. This client
never reads an .env file, follows redirects, starts services, or contacts a proxy.
Only sanitized check results and OpenAPI documents are written to artifacts.
"""

from __future__ import annotations

import base64
import contextlib
import dataclasses
import datetime as dt
import http.client
import importlib.util
import json
import logging
import math
import os
from pathlib import Path
import secrets
from http.cookies import SimpleCookie
import threading
import time
from typing import Any, Callable
from urllib.parse import urlsplit


MAX_RESPONSE_BYTES = 4 * 1024 * 1024
COOKIE_NAMES = ("unipo_sid", "unipo_csrf")
PROXY_VARIABLES = (
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy",
    "NO_PROXY", "no_proxy",
)


class CheckFailure(Exception):
    """Contains only a check identifier or a controlled, credential-free message."""


@dataclasses.dataclass(frozen=True)
class Target:
    label: str
    host: str
    port: int
    api_path: str
    authority: str

    @classmethod
    def parse(cls, label: str, raw: str) -> "Target":
        value = urlsplit(raw)
        if (
            value.scheme != "http"
            or value.hostname not in {"127.0.0.1", "localhost", "::1"}
            or value.port not in {28080, 28081}
            or value.username is not None
            or value.password is not None
            or value.query
            or value.fragment
            or value.path.rstrip("/") not in {"", "/api/v1"}
        ):
            raise CheckFailure("SETUP: HTTP targets must be the two loopback fixtures on ports 28080/28081")
        return cls(label, value.hostname, value.port, "/api/v1", value.netloc)

    @property
    def websocket_url(self) -> str:
        return "ws://" + self.authority + self.api_path + "/realtime/ws"


@dataclasses.dataclass(frozen=True, repr=False)
class Cookies:
    values: dict[str, str]

    def header(self) -> str:
        return "; ".join(name + "=" + self.values[name] for name in COOKIE_NAMES if name in self.values)


@dataclasses.dataclass(repr=False)
class Response:
    status: int
    payload: Any
    cookies: Cookies


@dataclasses.dataclass(repr=False)
class Device:
    accesses: list[str]
    refreshes: list[str]
    cookie_history: list[Cookies]
    ttl: int

    @property
    def access(self) -> str:
        return self.accesses[-1]

    @property
    def refresh(self) -> str:
        return self.refreshes[-1]

    @property
    def cookies(self) -> Cookies:
        return self.cookie_history[-1]


@dataclasses.dataclass(repr=False)
class User:
    account: str
    password: str
    user_id: int
    public_uid: str


class Suite:
    def __init__(self) -> None:
        required = (
            "UNIPO_DP02_HTTP_A", "UNIPO_DP02_HTTP_B", "UNIPO_DP02_ADMIN_ACCOUNT",
            "UNIPO_DP02_ADMIN_PASSWORD", "UNIPO_DP02_ARTIFACT_DIR",
        )
        if any(not os.environ.get(name) for name in required):
            raise CheckFailure("SETUP: required DP02 fixture environment variables are missing")
        self.a = Target.parse("A", os.environ["UNIPO_DP02_HTTP_A"])
        self.b = Target.parse("B", os.environ["UNIPO_DP02_HTTP_B"])
        if {self.a.port, self.b.port} != {28080, 28081}:
            raise CheckFailure("SETUP: HTTP_A and HTTP_B must refer to different fixture ports")
        self.admin_account = os.environ["UNIPO_DP02_ADMIN_ACCOUNT"]
        self.admin_password = os.environ["UNIPO_DP02_ADMIN_PASSWORD"]
        self.artifacts = Path(os.environ["UNIPO_DP02_ARTIFACT_DIR"]).expanduser().resolve()
        if self.artifacts == Path(self.artifacts.anchor):
            raise CheckFailure("SETUP: artifact output must be a dedicated directory")
        self.artifacts.mkdir(parents=True, exist_ok=True)
        self.secret_values: set[str] = {self.admin_account, self.admin_password}
        self.checks: list[dict[str, Any]] = []
        self.cases: list[dict[str, Any]] = []
        self.control: User | None = None
        self.control_device: Device | None = None
        self.revoked_access: str | None = None
        self.run_id = secrets.token_hex(6)
        self.ws_origin = os.environ.get("UNIPO_DP02_WS_ORIGIN", "http://127.0.0.1:5173")
        origin = urlsplit(self.ws_origin)
        if origin.scheme != "http" or origin.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise CheckFailure("SETUP: WebSocket Origin must be a loopback fixture origin")

    def check(self, condition: bool, label: str, **evidence: Any) -> None:
        self.checks.append({"check": label, "status": "PASS" if condition else "FAIL", **evidence})
        if not condition:
            raise CheckFailure(label)

    def expected(self, response: Response, status: int, label: str) -> Any:
        self.check(response.status == status, label, expected_http=status, actual_http=response.status)
        if status == 200:
            self.check(isinstance(response.payload, dict) and response.payload.get("code") == "OK", label + ".envelope")
            return response.payload.get("data")
        return None

    def no_private_fields(self, value: Any, label: str) -> None:
        def contains_private(item: Any) -> bool:
            if isinstance(item, dict):
                return any(str(key).replace("_", "").lower() in {"passwordhash", "authversion"}
                           or contains_private(child) for key, child in item.items())
            return isinstance(item, list) and any(contains_private(child) for child in item)
        self.check(not contains_private(value), label)

    def request(
        self, target: Target, method: str, path: str, *, body: Any = None,
        bearer: str | None = None, cookies: Cookies | None = None,
        csrf: bool = True, mixed_probe: bool = False, absolute: bool = False, timeout: float = 8,
    ) -> Response:
        if bearer is not None and cookies is not None and not mixed_probe:
            raise CheckFailure("CLIENT: Cookie and Bearer carriers must stay separate")
        if not path.startswith("/") or "\r" in path or "\n" in path:
            raise CheckFailure("CLIENT: invalid fixture request path")
        headers = {"Accept": "application/json", "X-Client": "dp02-acceptance"}
        if bearer is not None:
            headers["Authorization"] = "Bearer " + bearer
        if cookies is not None:
            headers["Cookie"] = cookies.header()
            if csrf and method not in {"GET", "HEAD", "OPTIONS"}:
                headers["X-CSRF-Token"] = cookies.values.get("unipo_csrf", "")
        encoded = None
        if body is not None:
            encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        connection = http.client.HTTPConnection(target.host, target.port, timeout=timeout)
        try:
            connection.request(method, path if absolute else target.api_path + path, body=encoded, headers=headers)
            response = connection.getresponse()
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise CheckFailure("CLIENT: fixture response exceeded the bounded read limit")
            try:
                payload = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                payload = None
            values: dict[str, str] = {}
            for name, value in response.getheaders():
                if name.lower() == "set-cookie":
                    parsed = SimpleCookie()
                    parsed.load(value)
                    for key in COOKIE_NAMES:
                        if key in parsed and parsed[key].value and parsed[key]["max-age"] != "0":
                            values[key] = parsed[key].value
                            self.secret_values.add(parsed[key].value)
            return Response(response.status, payload, Cookies(values))
        except CheckFailure:
            raise
        except Exception as failure:
            raise CheckFailure("HTTP transport failed (" + type(failure).__name__ + ")") from None
        finally:
            connection.close()

    def authenticated_device(self, response: Response, label: str) -> tuple[Device, dict[str, Any]]:
        data = self.expected(response, 200, label)
        self.check(isinstance(data, dict), label + ".auth-data")
        self.no_private_fields(data.get("user"), label + ".private-fields")
        self.check(all(isinstance(data.get(key), str) and data[key] for key in ("token", "refreshToken")), label + ".credentials")
        self.check(all(response.cookies.values.get(name) for name in COOKIE_NAMES), label + ".cookies")
        self.secret_values.update((data["token"], data["refreshToken"]))
        return Device([data["token"]], [data["refreshToken"]], [response.cookies], int(data["expiresInSeconds"])), data["user"]

    def new_user(self, target: Target, label: str) -> tuple[User, Device]:
        account = "dp02_" + self.run_id + "_" + secrets.token_hex(4) + "@example.test"
        password = secrets.token_urlsafe(32)
        code = str(secrets.randbelow(10000)).zfill(4)
        self.secret_values.update((account, password, code))
        self.expected(self.request(target, "POST", "/auth/verification-code",
                                   body={"email": account, "purpose": "register"}), 200, label + ".verification-request")
        response = self.request(target, "POST", "/auth/register", body={
            "email": account, "password": password, "confirmPassword": password,
            "verificationCode": code, "nickname": "DP02-" + secrets.token_hex(4),
        })
        device, profile = self.authenticated_device(response, label + ".register")
        return User(account, password, int(profile["id"]), str(profile["publicUid"])), device

    def login(self, user: User, target: Target, label: str) -> Device:
        response = self.request(target, "POST", "/auth/login", body={"email": user.account, "password": user.password})
        device, _ = self.authenticated_device(response, label)
        return device

    def admin(self, target: Target) -> Device:
        response = self.request(target, "POST", "/auth/login", body={"email": self.admin_account, "password": self.admin_password})
        device, _ = self.authenticated_device(response, "ADMIN.login." + target.label)
        return device

    def refresh_device(self, device: Device, target: Target, label: str) -> None:
        response = self.request(target, "POST", "/auth/refresh", body={"refreshToken": device.refresh})
        renewed, _ = self.authenticated_device(response, label)
        if renewed.access not in device.accesses:
            device.accesses.append(renewed.access)
        if renewed.refresh not in device.refreshes:
            device.refreshes.append(renewed.refresh)
        device.cookie_history.append(renewed.cookies)
        device.ttl = renewed.ttl

    def valid_device(self, device: Device, label: str) -> None:
        for target in (self.a, self.b):
            for carrier, kwargs in (("bearer", {"bearer": device.access}), ("cookie", {"cookies": device.cookies})):
                data = self.expected(self.request(target, "GET", "/auth/me", **kwargs), 200,
                                     label + "." + target.label + "." + carrier)
                self.no_private_fields(data, label + "." + target.label + "." + carrier + ".private-fields")

    def rejected_devices(self, devices: list[Device], label: str) -> None:
        for target in (self.a, self.b):
            for index, device in enumerate(devices, 1):
                prefix = label + "." + target.label + ".device" + str(index)
                for number, access in enumerate(device.accesses, 1):
                    self.expected(self.request(target, "GET", "/auth/me", bearer=access), 401, prefix + ".access" + str(number))
                for number, cookies in enumerate(device.cookie_history, 1):
                    self.expected(self.request(target, "GET", "/auth/me", cookies=cookies), 401, prefix + ".cookie" + str(number))
                for number, refresh in enumerate(device.refreshes, 1):
                    self.expected(self.request(target, "POST", "/auth/refresh", body={"refreshToken": refresh}),
                                  401, prefix + ".refresh" + str(number))

    def control_is_unchanged(self, label: str) -> None:
        if self.control_device is None:
            raise CheckFailure("CLIENT: control identity is unavailable")
        if self.token_expiry(self.control_device.access) - time.time() <= 40:
            # A short fixture access lifetime is not evidence of cross-user revocation.
            # Check the existing Cookie and refresh before issuing replacement credentials.
            for target in (self.a, self.b):
                self.expected(self.request(target, "GET", "/auth/me", cookies=self.control_device.cookies),
                              200, label + ".other-user-existing-cookie." + target.label)
            self.refresh_device(self.control_device, self.b, label + ".other-user-valid-refresh")
        self.valid_device(self.control_device, label + ".other-user")

    def status(self, user: User, value: str, target: Target, label: str) -> None:
        admin = self.admin(target)
        data = self.expected(self.request(target, "PUT", "/admin/users/" + str(user.user_id) + "/status",
                                          bearer=admin.access, body={"status": value}), 200, label)
        self.no_private_fields(data, label + ".private-fields")

    def role(self, user: User, value: str, target: Target, label: str) -> None:
        admin = self.admin(target)
        data = self.expected(self.request(target, "PUT", "/admin/users/" + str(user.user_id) + "/role",
                                          bearer=admin.access, body={"role": value, "operatorScope": "all"}), 200, label)
        self.no_private_fields(data, label + ".private-fields")

    def reset_case(self) -> None:
        user, first = self.new_user(self.a, "R01")
        second = self.login(user, self.b, "R01.second-device")
        self.valid_device(first, "R01.before")
        code = str(secrets.randbelow(10000)).zfill(4)
        password = secrets.token_urlsafe(32)
        self.secret_values.update((code, password))
        self.expected(self.request(self.b, "POST", "/auth/verification-code",
                                   body={"email": user.account, "purpose": "reset-password"}), 200, "R01.reset-code")
        self.expected(self.request(self.b, "POST", "/auth/reset-password", body={
            "email": user.account, "verificationCode": code, "password": password, "confirmPassword": password,
        }), 200, "R01.reset")
        self.rejected_devices([first, second], "R01.revoked")
        user.password = password
        self.valid_device(self.login(user, self.a, "R01.new-login"), "R01.new-password")
        self.control_is_unchanged("R01")

    def ban_case(self) -> None:
        user, first = self.new_user(self.b, "R02")
        second = self.login(user, self.a, "R02.second-device")
        self.status(user, "disabled", self.a, "R02.ban")
        self.rejected_devices([first, second], "R02.banned")
        self.status(user, "active", self.b, "R02.unban")
        self.rejected_devices([first, second], "R02.old-identities-stay-revoked")
        self.valid_device(self.login(user, self.a, "R02.new-login"), "R02.new-identity")
        self.control_is_unchanged("R02")

    def downgrade_case(self) -> None:
        user, _ = self.new_user(self.a, "R03")
        self.role(user, "OPERATOR", self.b, "R03.promote")
        first = self.login(user, self.a, "R03.operator-first")
        second = self.login(user, self.b, "R03.operator-second")
        data = self.expected(self.request(self.b, "GET", "/admin/analytics/overview", bearer=first.access),
                             200, "R03.before-operator-analytics")
        self.no_private_fields(data, "R03.analytics-private-fields")
        self.role(user, "USER", self.a, "R03.downgrade")
        self.rejected_devices([first, second], "R03.revoked")
        current = self.login(user, self.b, "R03.new-user-login")
        self.valid_device(current, "R03.new-user-identity")
        for target in (self.a, self.b):
            self.expected(self.request(target, "GET", "/admin/users", bearer=current.access), 403, "R03." + target.label + ".bearer-forbidden")
            self.expected(self.request(target, "GET", "/admin/users", cookies=current.cookies), 403, "R03." + target.label + ".cookie-forbidden")
            self.expected(self.request(target, "GET", "/admin/analytics/overview", bearer=current.access),
                          403, "R03." + target.label + ".user-analytics-forbidden")
        self.control_is_unchanged("R03")

    def operator_administration_case(self) -> None:
        target_user, target_device = self.new_user(self.b, "R09.target")
        for role in ("OPERATOR", "SCHOOL_OPERATOR", "COLLEGE_OPERATOR", "MAJOR_OPERATOR"):
            label = "R09." + role
            operator, _ = self.new_user(self.a, label)
            self.role(operator, role, self.b, label + ".admin-assigns-role")
            current = self.login(operator, self.a, label + ".operator-reauthenticates")
            for node, carrier, arguments in ((self.a, "bearer", {"bearer": current.access}),
                                             (self.b, "cookie", {"cookies": current.cookies})):
                self.expected(self.request(node, "PUT", "/admin/users/" + str(operator.user_id) + "/role",
                                           body={"role": "ADMIN", "operatorScope": "admin"}, **arguments),
                              403, label + "." + carrier + ".cannot-escalate-self")
                self.expected(self.request(node, "PUT", "/admin/users/" + str(target_user.user_id) + "/status",
                                           body={"status": "disabled"}, **arguments),
                              403, label + "." + carrier + ".cannot-ban-other-user")
                self.expected(self.request(node, "GET", "/admin/users", **arguments),
                              403, label + "." + carrier + ".cannot-list-users")
                self.expected(self.request(node, "GET", "/admin/analytics/overview", **arguments),
                              200, label + "." + carrier + ".analytics-remains-available")
                profile = self.expected(self.request(node, "GET", "/auth/me", **arguments),
                                        200, label + "." + carrier + ".identity-unchanged")
                self.check(profile.get("role") == role, label + "." + carrier + ".role-unchanged")
            self.valid_device(target_device, label + ".target-not-banned")

        self.status(target_user, "disabled", self.a, "R09.admin-can-ban")
        self.rejected_devices([target_device], "R09.admin-ban-is-effective")
        self.status(target_user, "active", self.b, "R09.admin-can-unban")
        target_device = self.login(target_user, self.a, "R09.target-reauthenticates")
        initial = self.expected(self.request(self.b, "GET", "/auth/me", bearer=target_device.access),
                                200, "R09.valid-target-before-invalid-values")
        for field, invalid_values in (("role", ("UNRECOGNIZED_ROLE", "", None)),
                                      ("status", ("unrecognized-status", "", None))):
            for index, invalid in enumerate(invalid_values, 1):
                node = self.a if index % 2 else self.b
                admin = self.admin(node)
                body = {field: invalid}
                if field == "role":
                    body["operatorScope"] = "student"
                self.expected(self.request(node, "PUT", "/admin/users/" + str(target_user.user_id) + "/" + field,
                                           bearer=admin.access, body=body), 400,
                              "R09.admin-invalid-" + field + str(index))
                after = self.expected(self.request(self.b, "GET", "/auth/me", bearer=target_device.access),
                                      200, "R09.invalid-" + field + str(index) + ".does-not-revoke-valid-target")
                self.check(after.get("role") == initial.get("role") and after.get("status") == initial.get("status"),
                           "R09.invalid-" + field + str(index) + ".does-not-mutate-user")
        self.valid_device(target_device, "R09.valid-target-preserved")
        self.control_is_unchanged("R09")

    def delete_case(self) -> None:
        user, first = self.new_user(self.a, "R04")
        second = self.login(user, self.b, "R04.second-device")
        self.expected(self.request(self.b, "DELETE", "/users/me", cookies=first.cookies), 200, "R04.delete")
        self.rejected_devices([first, second], "R04.revoked")
        self.control_is_unchanged("R04")

    def device_logout_case(self) -> None:
        user, first = self.new_user(self.b, "R05")
        second = self.login(user, self.a, "R05.second-device")
        self.refresh_device(first, self.a, "R05.refresh-first")
        self.refresh_device(first, self.b, "R05.refresh-second")
        self.check(len(first.accesses) >= 3, "R05.distinct-access-history")
        self.check(len({cookies.values.get("unipo_sid") for cookies in first.cookie_history}) >= 3,
                   "R05.distinct-cookie-history")
        self.expected(self.request(self.a, "POST", "/auth/logout", bearer=first.accesses[0],
                                   body={"refreshToken": first.refreshes[0]}), 200, "R05.logout-old-derived-credential")
        self.rejected_devices([first], "R05.family-revoked")
        self.revoked_access = first.accesses[0]
        self.valid_device(second, "R05.other-device-preserved")
        self.refresh_device(second, self.b, "R05.other-device-refresh")
        self.valid_device(second, "R05.other-device-after-refresh")
        self.control_is_unchanged("R05")

    def logout_all_case(self) -> None:
        user, first = self.new_user(self.a, "R06")
        second = self.login(user, self.b, "R06.second-device")
        self.expected(self.request(self.b, "POST", "/auth/logout-all", cookies=first.cookies), 200, "R06.logout-all")
        self.rejected_devices([first, second], "R06.revoked")
        self.control_is_unchanged("R06")

    def change_password_case(self) -> None:
        user, first = self.new_user(self.b, "R07")
        second = self.login(user, self.a, "R07.second-device")
        previous = user.password
        password = secrets.token_urlsafe(32)
        self.secret_values.add(password)
        self.expected(self.request(self.a, "POST", "/auth/change-password", bearer=first.access,
                                   body={"oldPassword": previous, "password": password, "confirmPassword": password}), 200, "R07.change-password")
        self.rejected_devices([first, second], "R07.revoked")
        old_login = self.request(self.b, "POST", "/auth/login", body={"email": user.account, "password": previous})
        self.check(old_login.status in {400, 401}, "R07.old-password-rejected", actual_http=old_login.status)
        user.password = password
        self.valid_device(self.login(user, self.b, "R07.new-login"), "R07.new-password")
        self.control_is_unchanged("R07")

    def csrf_and_carrier_case(self) -> None:
        _, current = self.new_user(self.a, "R08")
        self.expected(self.request(self.b, "POST", "/auth/heartbeat", cookies=current.cookies, csrf=False), 403, "R08.cookie-without-csrf")
        self.expected(self.request(self.b, "POST", "/auth/heartbeat", cookies=current.cookies), 200, "R08.cookie-with-csrf")
        self.expected(self.request(self.a, "POST", "/auth/heartbeat", bearer=current.access), 200, "R08.bearer-without-csrf")
        self.check(self.revoked_access is not None, "R08.revoked-bearer-fixture")
        for target in (self.a, self.b):
            self.expected(self.request(target, "GET", "/auth/me", bearer=self.revoked_access,
                                       cookies=current.cookies, mixed_probe=True), 401, "R08." + target.label + ".no-cookie-fallback")
        self.valid_device(current, "R08.valid-cookie-remains-valid")

    def export_openapi(self) -> None:
        for target in (self.a, self.b):
            response = self.request(target, "GET", "/v3/api-docs", absolute=True)
            self.check(response.status == 200 and isinstance(response.payload, dict) and "openapi" in response.payload,
                       "DOCS." + target.label, actual_http=response.status)
            self.write_json("openapi-" + target.label + ".json", self.redact(response.payload))

    def privacy_case(self) -> None:
        for target in (self.a, self.b):
            admin = self.admin(target)
            data = self.expected(self.request(target, "GET", "/admin/users", bearer=admin.access), 200, "PRIVACY." + target.label + ".admin-users")
            self.no_private_fields(data, "PRIVACY." + target.label + ".admin-users-fields")
            self.no_private_fields(self.expected(self.request(target, "GET", "/users/me", cookies=admin.cookies),
                                                  200, "PRIVACY." + target.label + ".users-me"),
                                   "PRIVACY." + target.label + ".users-me-fields")

    @contextlib.contextmanager
    def direct_websocket_environment(self):
        saved = {name: os.environ.get(name) for name in PROXY_VARIABLES}
        try:
            for name in PROXY_VARIABLES:
                os.environ.pop(name, None)
            os.environ["NO_PROXY"] = os.environ["no_proxy"] = "127.0.0.1,localhost,::1"
            yield
        finally:
            for name, value in saved.items():
                if value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = value

    def websocket_case(self, action: str) -> None:
        if importlib.util.find_spec("websocket") is None:
            raise CheckFailure("WS dependency unavailable: websocket-client is required by the fixture interpreter")
        import websocket
        websocket.enableTrace(False)
        logging.getLogger("websocket").setLevel(logging.CRITICAL)
        label = "WS." + action
        mutation_node, socket_node = ((self.a, self.b) if action in {"ban", "reset-password", "delete", "logout-device"}
                                      else (self.b, self.a))
        user, first = self.new_user(mutation_node, label)
        second = self.login(user, socket_node, label + ".second-device")
        if action == "downgrade":
            self.role(user, "OPERATOR", mutation_node, label + ".promote")
            first = self.login(user, mutation_node, label + ".operator-first")
            second = self.login(user, socket_node, label + ".operator-second")
        peer, peer_device = self.new_user(mutation_node, label + ".peer")
        conversation = self.expected(self.request(mutation_node, "POST", "/messages/conversations",
                                                   bearer=peer_device.access, body={"peerUid": user.public_uid}), 200, label + ".conversation")
        conversation_id = int(conversation["id"])
        sockets: list[Any] = []
        preserved: list[Any] = []
        try:
            with self.direct_websocket_environment():
                cookie_socket = websocket.create_connection(socket_node.websocket_url, timeout=2,
                                                              cookie=first.cookies.header(), origin=self.ws_origin)
                sockets.append(cookie_socket)
                self.ws_ready(cookie_socket, websocket, label + ".cookie-ready", user.user_id)
                bearer_socket = websocket.create_connection(socket_node.websocket_url, timeout=2, origin=self.ws_origin)
                sockets.append(bearer_socket)
                bearer_socket.send(json.dumps({"type": "auth", "token": first.access if action == "logout-device" else second.access}))
                self.ws_ready(bearer_socket, websocket, label + ".bearer-ready", user.user_id)
                if action == "logout-device":
                    connection = websocket.create_connection(socket_node.websocket_url, timeout=2,
                                                              cookie=second.cookies.header(), origin=self.ws_origin)
                    preserved.append(connection)
                    self.ws_ready(connection, websocket, label + ".other-device-cookie-ready", user.user_id)
                    connection = websocket.create_connection(socket_node.websocket_url, timeout=2, origin=self.ws_origin)
                    preserved.append(connection)
                    connection.send(json.dumps({"type": "auth", "token": second.access}))
                    self.ws_ready(connection, websocket, label + ".other-device-bearer-ready", user.user_id)

            self.expected(self.request(mutation_node, "POST", "/messages/conversations/" + str(conversation_id) + "/messages",
                                       bearer=peer_device.access, body={"content": "DP02 baseline event"}), 200, label + ".baseline-message")
            for index, connection in enumerate(sockets + preserved, 1):
                self.ws_message(connection, websocket, label + ".baseline-received" + str(index), conversation_id)

            self.websocket_mutation(action, user, first, mutation_node, label)
            finished = time.monotonic()
            observations: list[dict[str, Any]] = [{}, {}]
            readers = [threading.Thread(target=self.ws_observe_revoked, args=(connection, websocket, finished, observations[index]),
                                        daemon=True) for index, connection in enumerate(sockets)]
            for reader in readers:
                reader.start()
            try:
                self.expected(self.request(mutation_node, "POST", "/messages/conversations/" + str(conversation_id) + "/messages",
                                           bearer=peer_device.access, body={"content": "DP02 event after identity revocation"}),
                              200, label + ".post-mutation-message")
                for index, connection in enumerate(preserved, 1):
                    self.ws_message(connection, websocket, label + ".other-device-still-receives" + str(index), conversation_id)
            finally:
                for reader in readers:
                    reader.join(max(0.0, finished + 5.25 - time.monotonic()))
            for index, observation in enumerate(observations, 1):
                self.check(not observation.get("private_event", False), label + ".no-private-event" + str(index))
                self.check(observation.get("closed", False) and observation.get("elapsed", 99) <= 5.0,
                           label + ".closed-within-five-seconds" + str(index),
                           close_seconds=round(observation.get("elapsed", 99), 3))
            self.rejected_devices([first] if action == "logout-device" else [first, second], label + ".http-revoked")
            if action == "logout-device":
                self.valid_device(second, label + ".other-device-http-preserved")
            self.valid_device(peer_device, label + ".peer-preserved")
        finally:
            for connection in sockets + preserved:
                try:
                    connection.close(timeout=0.1)
                except Exception:
                    pass

    def websocket_mutation(self, action: str, user: User, device: Device, target: Target, label: str) -> None:
        if action == "ban":
            self.status(user, "disabled", target, label + ".mutation")
        elif action == "logout-all":
            self.expected(self.request(target, "POST", "/auth/logout-all", cookies=device.cookies), 200, label + ".mutation")
        elif action == "logout-device":
            self.expected(self.request(target, "POST", "/auth/logout", bearer=device.access,
                                       body={"refreshToken": device.refresh}), 200, label + ".mutation")
        elif action == "delete":
            self.expected(self.request(target, "DELETE", "/users/me", cookies=device.cookies), 200, label + ".mutation")
        elif action == "downgrade":
            self.role(user, "USER", target, label + ".mutation")
        elif action in {"reset-password", "change-password"}:
            password = secrets.token_urlsafe(32)
            self.secret_values.add(password)
            if action == "reset-password":
                code = str(secrets.randbelow(10000)).zfill(4)
                self.secret_values.add(code)
                self.expected(self.request(target, "POST", "/auth/verification-code",
                                           body={"email": user.account, "purpose": "reset-password"}),
                              200, label + ".verification-request")
                self.expected(self.request(target, "POST", "/auth/reset-password", body={
                    "email": user.account, "verificationCode": code, "password": password, "confirmPassword": password,
                }), 200, label + ".mutation")
            else:
                self.expected(self.request(target, "POST", "/auth/change-password", bearer=device.access, body={
                    "oldPassword": user.password, "password": password, "confirmPassword": password,
                }), 200, label + ".mutation")
            user.password = password
        else:
            raise CheckFailure("WS: unsupported identity mutation")

    def ws_ready(self, connection: Any, websocket: Any, label: str, user_id: int) -> None:
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                value = json.loads(connection.recv())
            except websocket.WebSocketTimeoutException:
                continue
            except Exception:
                raise CheckFailure(label + ": ready was not received") from None
            if isinstance(value, dict) and value.get("type") == "ready":
                self.check(value.get("userId") == user_id, label)
                return
            if not isinstance(value, dict) or value.get("type") != "auth-required":
                raise CheckFailure(label + ": unexpected handshake frame")
        raise CheckFailure(label + ": ready deadline exceeded")

    def ws_message(self, connection: Any, websocket: Any, label: str, conversation_id: int) -> None:
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                value = json.loads(connection.recv())
            except websocket.WebSocketTimeoutException:
                continue
            except Exception:
                raise CheckFailure(label + ": baseline event was not received") from None
            if isinstance(value, dict) and value.get("type") == "event" and value.get("event") == "message":
                payload = value.get("payload")
                if isinstance(payload, dict) and payload.get("conversationId") == conversation_id:
                    self.check(True, label)
                    return
        raise CheckFailure(label + ": baseline event deadline exceeded")

    @staticmethod
    def ws_observe_revoked(connection: Any, websocket: Any, start: float, observation: dict[str, Any]) -> None:
        deadline = start + 5.0
        connection.settimeout(0.2)
        while time.monotonic() <= deadline:
            try:
                opcode, frame = connection.recv_data_frame(control_frame=True)
                if opcode == websocket.ABNF.OPCODE_CLOSE:
                    observation.update(closed=True, elapsed=time.monotonic() - start)
                    return
                if opcode == websocket.ABNF.OPCODE_TEXT:
                    value = json.loads(frame.data)
                    if isinstance(value, dict) and value.get("type") == "event":
                        observation["private_event"] = True
            except websocket.WebSocketTimeoutException:
                continue
            except websocket.WebSocketConnectionClosedException:
                observation.update(closed=True, elapsed=time.monotonic() - start)
                return
            except Exception:
                observation["reader_error"] = True
                return

    @staticmethod
    def ws_observe_expiry(connection: Any, websocket: Any, expires_monotonic: float, observation: dict[str, Any]) -> None:
        connection.settimeout(0.2)
        while time.monotonic() <= expires_monotonic + 5:
            try:
                opcode, frame = connection.recv_data_frame(control_frame=True)
                if opcode == websocket.ABNF.OPCODE_CLOSE:
                    observation.update(closed=True, seconds_after_expiry=time.monotonic() - expires_monotonic)
                    return
                if opcode == websocket.ABNF.OPCODE_TEXT:
                    value = json.loads(frame.data)
                    if time.monotonic() >= expires_monotonic and isinstance(value, dict) and value.get("type") == "event":
                        observation["private_event_after_expiry"] = True
            except websocket.WebSocketTimeoutException:
                continue
            except websocket.WebSocketConnectionClosedException:
                observation.update(closed=True, seconds_after_expiry=time.monotonic() - expires_monotonic)
                return
            except Exception:
                observation["reader_error"] = True
                return

    def expired_access_logout_case(self) -> None:
        user, first = self.new_user(self.a, "EXPIRED")
        second = self.login(user, self.b, "EXPIRED.other-device")
        self.check(0 < first.ttl <= 70, "EXPIRED.fixture-access-ttl-at-most-seventy-seconds")
        expires = self.token_expiry(first.access)
        remaining = expires - time.time() + 1.25
        self.check(math.isfinite(remaining) and remaining <= 75, "EXPIRED.bounded-wait")
        self.check(self.token_expiry(first.refresh) - time.time() > max(0.0, remaining) + 30,
                   "EXPIRED.refresh-covers-wait-and-request-budget")
        if importlib.util.find_spec("websocket") is None:
            raise CheckFailure("WS dependency unavailable: websocket-client is required")
        import websocket
        websocket.enableTrace(False)
        connection = None
        try:
            with self.direct_websocket_environment():
                connection = websocket.create_connection(self.b.websocket_url, timeout=2, origin=self.ws_origin)
                connection.send(json.dumps({"type": "auth", "token": first.access}))
                self.ws_ready(connection, websocket, "EXPIRED.ws-ready-before-expiry", user.user_id)
            expires_monotonic = time.monotonic() + expires - time.time()
            observation: dict[str, Any] = {}
            reader = threading.Thread(target=self.ws_observe_expiry,
                                      args=(connection, websocket, expires_monotonic, observation), daemon=True)
            reader.start()
            deadline = expires_monotonic + 1.25
            while time.monotonic() < deadline:
                time.sleep(max(0.0, min(1.0, deadline - time.monotonic())))
            # Observe natural expiration before logout could independently cause the close.
            reader.join(max(0.0, expires_monotonic + 5.25 - time.monotonic()))
            elapsed = observation.get("seconds_after_expiry", 99)
            self.check(not observation.get("private_event_after_expiry", False), "EXPIRED.no-private-event-after-expiry")
            self.check(observation.get("closed", False) and -0.5 <= elapsed <= 5,
                       "EXPIRED.ws-naturally-closes-within-five-seconds", close_seconds_after_expiry=round(elapsed, 3))
        finally:
            if connection is not None:
                try:
                    connection.close(timeout=0.1)
                except Exception:
                    pass
        for target in (self.a, self.b):
            self.expected(self.request(target, "GET", "/auth/me", bearer=first.access), 401, "EXPIRED." + target.label + ".access-rejected")
        self.expected(self.request(self.b, "POST", "/auth/logout", bearer=first.access,
                                   body={"refreshToken": first.refresh}), 200, "EXPIRED.logout-with-valid-refresh")
        self.rejected_devices([first], "EXPIRED.family-revoked")
        self.refresh_device(second, self.a, "EXPIRED.other-device-refresh")
        self.valid_device(second, "EXPIRED.other-device-preserved")
        if self.control is not None:
            self.control_device = self.login(self.control, self.b, "EXPIRED.control-login")
            self.control_is_unchanged("EXPIRED")

    @staticmethod
    def token_expiry(access: str) -> float:
        # Used only to schedule assertions; decoded claims never authenticate an identity.
        try:
            encoded = access.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
            expires = float(claims["exp"])
            if not math.isfinite(expires):
                raise ValueError("Invalid expiry")
            return expires
        except Exception:
            raise CheckFailure("CLIENT: could not determine the fixture access expiry") from None

    def redact(self, value: Any) -> Any:
        if isinstance(value, dict):
            return {key: self.redact(child) for key, child in value.items()}
        if isinstance(value, list):
            return [self.redact(child) for child in value]
        if isinstance(value, str):
            for secret in sorted(self.secret_values, key=len, reverse=True):
                if len(secret) >= 4:
                    value = value.replace(secret, "<redacted>")
        return value

    def write_json(self, name: str, value: Any) -> None:
        path = self.artifacts / name
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        path.chmod(0o600)

    def save_results(self) -> None:
        self.write_json("identity-http-results.json", {
            "run": self.run_id,
            "completed_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "targets": ["loopback-A", "loopback-B"],
            "cases": self.cases,
            "checks": self.checks,
            "credentials_recorded": False,
        })

    def run_case(self, label: str, operation: Callable[[], None]) -> None:
        print("RUN " + label, flush=True)
        started = time.monotonic()
        previous = len(self.checks)
        result: dict[str, Any] = {"case": label}
        try:
            operation()
            result["status"] = "PASS"
        except CheckFailure as failure:
            result.update(status="FAIL", reason=str(failure))
        except Exception as failure:
            result.update(status="FAIL", reason="Unexpected " + type(failure).__name__)
        result.update(checks=len(self.checks) - previous, seconds=round(time.monotonic() - started, 3))
        self.cases.append(result)
        self.save_results()
        print(result["status"] + " " + label + " (" + str(result["checks"]) + " checks)", flush=True)

    def run(self) -> int:
        self.run_case("OPENAPI", self.export_openapi)
        self.control, self.control_device = self.new_user(self.a, "CONTROL")
        for label, operation in (
            ("R01-reset-password", self.reset_case),
            ("R02-ban-unban", self.ban_case),
            ("R03-role-downgrade", self.downgrade_case),
            ("R04-delete-account", self.delete_case),
            ("R05-device-token-family", self.device_logout_case),
            ("R06-logout-all", self.logout_all_case),
            ("R07-change-password", self.change_password_case),
            ("R08-csrf-and-carrier-isolation", self.csrf_and_carrier_case),
            ("R09-operator-administration-boundary", self.operator_administration_case),
            ("RESPONSE-PRIVACY", self.privacy_case),
            ("WS-ban", lambda: self.websocket_case("ban")),
            ("WS-logout-all", lambda: self.websocket_case("logout-all")),
            ("WS-reset-password", lambda: self.websocket_case("reset-password")),
            ("WS-change-password", lambda: self.websocket_case("change-password")),
            ("WS-delete-account", lambda: self.websocket_case("delete")),
            ("WS-role-downgrade", lambda: self.websocket_case("downgrade")),
            ("WS-current-device-logout", lambda: self.websocket_case("logout-device")),
            ("EXPIRED-access-valid-refresh-logout", self.expired_access_logout_case),
        ):
            self.run_case(label, operation)
        failed = sum(case["status"] != "PASS" for case in self.cases)
        print("DP02 cases: " + str(len(self.cases) - failed) + " PASS, " + str(failed) + " FAIL", flush=True)
        return int(failed != 0)


def main() -> int:
    suite: Suite | None = None
    try:
        suite = Suite()
        return suite.run()
    except CheckFailure as failure:
        print("FAIL " + str(failure), flush=True)
    except Exception as failure:
        print("FAIL setup (" + type(failure).__name__ + ")", flush=True)
    if suite is not None:
        suite.cases.append({"case": "SETUP", "status": "FAIL", "reason": "Fixture setup or control identity failed"})
        suite.save_results()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
