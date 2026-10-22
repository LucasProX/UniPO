#!/usr/bin/env python3
"""Confirm DP02-R01 on archived aef8dcc, using disposable loopback fixtures only.

This is a defect-reproduction probe, never a passing acceptance result. The
wrapper must select the archived jar, create fresh MySQL/Redis, and own cleanup.
No response body, credential, password, verification code, or Cookie is logged.
"""

from __future__ import annotations

import dataclasses
import datetime as dt
import http.client
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import secrets
import time
from typing import Any
from urllib.parse import urlsplit


BASELINE_REVISION = "aef8dcc48e9f9407d08c907336ab99a2277690d2"


class ProbeError(Exception):
    """Only controlled identifiers and HTTP status numbers are allowed here."""


@dataclasses.dataclass(frozen=True)
class Target:
    label: str
    host: str
    port: int

    @classmethod
    def parse(cls, label: str, raw: str) -> "Target":
        value = urlsplit(raw)
        if (value.scheme != "http" or value.hostname not in {"127.0.0.1", "localhost", "::1"}
                or value.port not in {28080, 28081} or value.username is not None
                or value.password is not None or value.query or value.fragment
                or value.path.rstrip("/") not in {"", "/api/v1"}):
            raise ProbeError("Only the two isolated loopback fixture targets are allowed")
        return cls(label, value.hostname, value.port)


@dataclasses.dataclass(repr=False)
class Response:
    status: int
    payload: Any
    cookies: dict[str, str]


@dataclasses.dataclass(repr=False)
class Device:
    access: str
    refresh: str
    cookies: dict[str, str]


class BaselineProbe:
    def __init__(self) -> None:
        if os.environ.get("UNIPO_DP02_BASELINE") != "1":
            raise ProbeError("Explicit isolated legacy-baseline mode is required")
        required = ("UNIPO_DP02_HTTP_A", "UNIPO_DP02_HTTP_B", "UNIPO_DP02_ARTIFACT_DIR")
        if any(not os.environ.get(name) for name in required):
            raise ProbeError("Required baseline fixture environment variables are missing")
        self.a = Target.parse("A", os.environ["UNIPO_DP02_HTTP_A"])
        self.b = Target.parse("B", os.environ["UNIPO_DP02_HTTP_B"])
        if {self.a.port, self.b.port} != {28080, 28081}:
            raise ProbeError("The baseline requires two distinct fixture ports")
        self.artifacts = Path(os.environ["UNIPO_DP02_ARTIFACT_DIR"]).expanduser().resolve()
        if self.artifacts == Path(self.artifacts.anchor):
            raise ProbeError("Artifacts require a dedicated fixture directory")
        self.artifacts.mkdir(parents=True, exist_ok=True)
        self.observations: list[dict[str, Any]] = []
        self.private_values: set[str] = set()

    def request(self, target: Target, method: str, path: str, label: str, *, body: Any = None,
                bearer: str | None = None, cookies: dict[str, str] | None = None) -> Response:
        if bearer is not None and cookies is not None:
            raise ProbeError("The baseline probe never mixes Cookie and Bearer carriers")
        headers = {"Accept": "application/json", "X-Client": "dp02-legacy-baseline"}
        if bearer is not None:
            headers["Authorization"] = "Bearer " + bearer
        if cookies is not None:
            headers["Cookie"] = "; ".join(name + "=" + value for name, value in cookies.items())
            if method not in {"GET", "HEAD", "OPTIONS"}:
                headers["X-CSRF-Token"] = cookies.get("unipo_csrf", "")
        encoded = None
        if body is not None:
            encoded = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        connection = http.client.HTTPConnection(target.host, target.port, timeout=8)
        try:
            connection.request(method, "/api/v1" + path, body=encoded, headers=headers)
            received = connection.getresponse()
            raw = received.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ProbeError("Fixture response exceeded the bounded read limit")
            try:
                payload = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                payload = None
            values: dict[str, str] = {}
            for name, value in received.getheaders():
                if name.lower() == "set-cookie":
                    parsed = SimpleCookie()
                    parsed.load(value)
                    for key in ("unipo_sid", "unipo_csrf"):
                        if key in parsed and parsed[key].value:
                            values[key] = parsed[key].value
                            self.private_values.add(parsed[key].value)
            code = payload.get("code") if isinstance(payload, dict) else None
            request_id = payload.get("requestId") if isinstance(payload, dict) else None
            request_id = request_id or received.getheader("X-Request-Id")
            self.observations.append({
                "step": label, "node": target.label, "method": method, "path": path,
                "httpStatus": received.status,
                "code": self.safe_identifier(code, r"[A-Z][A-Z0-9_]{0,63}"),
                "requestId": self.safe_identifier(request_id, r"[A-Za-z0-9_-]{1,64}"),
            })
            return Response(received.status, payload, values)
        except ProbeError:
            raise
        except Exception as failure:
            raise ProbeError("HTTP transport failed (" + type(failure).__name__ + ")") from None
        finally:
            connection.close()

    def safe_identifier(self, value: Any, pattern: str) -> str | None:
        if value is None:
            return None
        if isinstance(value, str) and re.fullmatch(pattern, value) and value not in self.private_values:
            return value
        return "<redacted>"

    @staticmethod
    def require_ok(response: Response, label: str) -> Any:
        if (response.status != 200 or not isinstance(response.payload, dict)
                or response.payload.get("code") != "OK"):
            raise ProbeError(label + " prerequisite failed (HTTP " + str(response.status) + ")")
        return response.payload.get("data")

    def device(self, response: Response, label: str) -> Device:
        data = self.require_ok(response, label)
        if not isinstance(data, dict) or not all(isinstance(data.get(name), str) and data[name]
                                                for name in ("token", "refreshToken")):
            raise ProbeError(label + " did not issue complete credentials")
        if not all(response.cookies.get(name) for name in ("unipo_sid", "unipo_csrf")):
            raise ProbeError(label + " did not issue independent Cookie credentials")
        self.private_values.update((data["token"], data["refreshToken"]))
        return Device(data["token"], data["refreshToken"], response.cookies)

    def run(self) -> bool:
        account = "dp02_baseline_" + secrets.token_hex(8) + "@example.test"
        original = secrets.token_urlsafe(32)
        replacement = secrets.token_urlsafe(32)
        code = str(secrets.randbelow(10000)).zfill(4)
        self.private_values.update((account, original, replacement, code))
        self.require_ok(self.request(self.a, "POST", "/auth/verification-code", "setup.register-code",
                                     body={"email": account, "purpose": "register"}), "register-code")
        first = self.device(self.request(self.a, "POST", "/auth/register", "setup.register-device-one", body={
            "email": account, "password": original, "confirmPassword": original,
            "verificationCode": code, "nickname": "Baseline-" + secrets.token_hex(3),
        }), "register")
        # The archived issuer has no random jti; cross one second to get distinct historical JWTs.
        time.sleep(1.05)
        second = self.device(self.request(self.b, "POST", "/auth/login", "setup.login-device-two",
                                           body={"email": account, "password": original}), "second-login")
        if first.cookies["unipo_sid"] == second.cookies["unipo_sid"] or first.access == second.access:
            raise ProbeError("The two-device baseline prerequisites were not distinct")
        for target, device in ((self.b, first), (self.a, second)):
            self.require_ok(self.request(target, "GET", "/auth/me", "before-reset.old-access", bearer=device.access), "before-reset-access")
            self.require_ok(self.request(target, "GET", "/auth/me", "before-reset.old-cookie", cookies=device.cookies), "before-reset-cookie")

        self.require_ok(self.request(self.b, "POST", "/auth/verification-code", "mutation.reset-code",
                                     body={"email": account, "purpose": "reset-password"}), "reset-code")
        self.require_ok(self.request(self.b, "POST", "/auth/reset-password", "mutation.reset-password", body={
            "email": account, "password": replacement, "confirmPassword": replacement, "verificationCode": code,
        }), "reset-password")
        self.require_ok(self.request(self.a, "POST", "/auth/login", "after-reset.replacement-password-login",
                                     body={"email": account, "password": replacement}), "replacement-login")
        rejected_password = self.request(self.b, "POST", "/auth/login", "after-reset.old-password-login",
                                         body={"email": account, "password": original})
        if rejected_password.status not in {400, 401}:
            raise ProbeError("The password reset did not demonstrably replace the previous password")

        accepted: list[bool] = []
        for target in (self.a, self.b):
            for number, device in enumerate((first, second), 1):
                prefix = "after-reset.device" + str(number)
                for carrier, arguments in (("access", {"bearer": device.access}), ("cookie", {"cookies": device.cookies})):
                    for path, suffix in (("/auth/me", "me"), ("/messages/conversations", "conversations")):
                        response = self.request(target, "GET", path, prefix + ".old-" + carrier + "." + suffix, **arguments)
                        accepted.append(response.status == 200 and isinstance(response.payload, dict)
                                        and response.payload.get("code") == "OK")
                response = self.request(target, "POST", "/auth/refresh", prefix + ".old-refresh",
                                        body={"refreshToken": device.refresh})
                accepted.append(response.status == 200 and isinstance(response.payload, dict)
                                and response.payload.get("code") == "OK")
        return len(accepted) == 20 and all(accepted)

    def save(self, classification: str, reason: str | None = None) -> None:
        result: dict[str, Any] = {
            "scenario": "DP02-R01-reset-password",
            "baseline_revision": BASELINE_REVISION,
            "classification": classification,
            "acceptance_pass": False,
            "expected_after_reset_http": 401,
            "confirmed_defect": classification == "CONFIRMED_DEFECT",
            "completed_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "observations": self.observations,
            "credentials_recorded": False,
        }
        if reason is not None:
            result["reason"] = reason
        path = self.artifacts / "identity-baseline-results.json"
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        path.chmod(0o600)


def main() -> int:
    probe: BaselineProbe | None = None
    try:
        probe = BaselineProbe()
        reproduced = probe.run()
        classification = "CONFIRMED_DEFECT" if reproduced else "NOT_REPRODUCED"
        probe.save(classification)
        print(classification + " DP02-R01 (defect baseline; acceptance_pass=false)", flush=True)
        return 0 if reproduced else 1
    except ProbeError as failure:
        if probe is not None:
            probe.save("PROBE_ERROR", str(failure))
        print("PROBE_ERROR " + str(failure), flush=True)
    except Exception as failure:
        if probe is not None:
            probe.save("PROBE_ERROR", "Unexpected " + type(failure).__name__)
        print("PROBE_ERROR " + type(failure).__name__, flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
