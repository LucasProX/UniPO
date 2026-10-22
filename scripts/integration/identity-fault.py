#!/usr/bin/env python3
"""DP02 fault assertions; only the owning wrapper may restart fixture resources."""

from __future__ import annotations

import contextlib
import importlib.util
import json
import logging
import os
from pathlib import Path
import socket
import stat
import sys
import threading
import time
from typing import Any


sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("dp02_http_helpers", Path(__file__).with_name("identity-http.py"))
if _spec is None or _spec.loader is None:
    raise SystemExit("FAIL fault client helper is unavailable")
_base = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = _base
_spec.loader.exec_module(_base)


class FaultSuite(_base.Suite):
    def __init__(self) -> None:
        if os.environ.get("UNIPO_DP02_FAULT") != "1" or os.environ.get("UNIPO_DP02_BASELINE") == "1":
            raise _base.CheckFailure("SETUP: explicit current-version fault-fixture mode is required")
        super().__init__()
        raw = os.environ.get("UNIPO_DP02_CONTROL_SOCKET", "")
        self.control_socket = Path(raw)
        if (not raw or not self.control_socket.is_absolute()
                or self.control_socket.parent.resolve() != self.artifacts or self.control_socket.name != "control.sock"):
            raise _base.CheckFailure("SETUP: control socket must belong to this artifact directory")
        info = self.control_socket.lstat()
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise _base.CheckFailure("SETUP: control socket ownership or private permissions are invalid")
        self.actions: list[dict[str, Any]] = []
        self.fault_observations: list[dict[str, Any]] = []
        self.recovery_required: set[str] = set()
        self.revoked_devices: list[Any] = []
        self.uncovered = [
            "Application restart is injected on instance A; instance B remains running as the cross-instance contrast.",
            "Cookie-index behavior is checked through HTTP and logout-all; this client does not enumerate Redis key TTLs or set membership.",
            "Fault injection covers owned process/container stop, start, and restart; host crashes, replica failover, and distributed network partitions are not injected.",
            "Replay availability has a healthy positive control; exact cursor exclusivity, ordering, and duplicate suppression remain outside this identity-fault probe.",
        ]

    def service_action(self, action: str) -> None:
        allowed = {"restart_a", "restart_b", "stop_mysql", "start_mysql", "stop_redis", "start_redis"}
        if action not in allowed:
            raise _base.CheckFailure("CLIENT: unsupported fixture control action")
        request = json.dumps({"action": action}).encode("ascii") + b"\n"
        started = time.monotonic()
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(120)
        try:
            connection.connect(str(self.control_socket))
            connection.sendall(request)
            data = bytearray()
            while b"\n" not in data and len(data) <= 4096:
                block = connection.recv(4096 - len(data) + 1)
                if not block:
                    break
                data.extend(block)
            if len(data) > 4096:
                raise _base.CheckFailure("CONTROL: bounded response exceeded")
            response = json.loads(bytes(data).split(b"\n", 1)[0])
            ok = isinstance(response, dict) and response.get("ok") is True
            phase = response.get("phase") if isinstance(response, dict) else None
            self.actions.append({"action": action, "ok": ok, "seconds": round(time.monotonic() - started, 3),
                                 "phase": phase if isinstance(phase, str) and len(phase) < 80 else None})
            self.check(ok, "CONTROL." + action)
        except _base.CheckFailure:
            raise
        except Exception as failure:
            self.actions.append({"action": action, "ok": False, "failure_type": type(failure).__name__})
            raise _base.CheckFailure("CONTROL." + action + ": " + type(failure).__name__) from None
        finally:
            connection.close()

    @contextlib.contextmanager
    def outage(self, service: str):
        # Recovery is attempted even when the stop reply is lost after the action completed.
        self.recovery_required.add(service)
        original_error: BaseException | None = None
        recovery_error: BaseException | None = None
        try:
            self.service_action("stop_" + service)
            yield
        except BaseException as failure:
            original_error = failure
        finally:
            try:
                self.service_action("start_" + service)
            except BaseException as failure:
                recovery_error = failure
            else:
                self.recovery_required.discard(service)
            if original_error is not None or recovery_error is not None:
                def reason(error):
                    return str(error) if isinstance(error, _base.CheckFailure) else type(error).__name__ if error else None
                original_reason, recovery_reason = reason(original_error), reason(recovery_error)
                self.fault_observations.append({"operation": "outage-and-recovery-errors", "service": service,
                                                "original_failure": original_reason, "recovery_failure": recovery_reason})
                if original_error is not None:
                    if recovery_error is not None:
                        raise _base.CheckFailure("OUTAGE." + service + ": original=" + original_reason
                                                 + "; recovery=" + recovery_reason) from original_error
                    raise original_error
                raise recovery_error

    def unexpired(self, device: Any, label: str, budget: float = 90) -> None:
        self.check(self.token_expiry(device.access) - time.time() > budget, label + ".unexpired-access-premise")
        self.check(self.token_expiry(device.refresh) - time.time() > budget, label + ".unexpired-refresh-premise")

    def denied_without_private_data(self, response: Any, label: str) -> None:
        self.check(response.status in {401, 403, 500, 503}, label + ".http-denied", actual_http=response.status)
        self.check(isinstance(response.payload, dict) and response.payload.get("code") != "OK"
                   and response.payload.get("data") is None, label + ".no-private-data")
        self.no_private_fields(response.payload, label + ".no-internal-fields")

    def await_original_devices(self, devices: list[tuple[Any, int]], label: str, timeout: float = 30) -> None:
        started = time.monotonic()
        deadline = started + timeout
        attempts = 0
        transient_statuses: set[int] = set()
        while time.monotonic() < deadline:
            attempts += 1
            ready = True
            for device, user_id in devices:
                if not ready:
                    break
                for target in (self.a, self.b):
                    if not ready:
                        break
                    for arguments in ({"bearer": device.access}, {"cookies": device.cookies}):
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            ready = False
                            break
                        try:
                            response = self.request(target, "GET", "/auth/me", timeout=min(3.0, remaining), **arguments)
                        except _base.CheckFailure:
                            transient_statuses.add(0)
                            ready = False
                            break
                        if (response.status != 200 or not isinstance(response.payload, dict)
                                or response.payload.get("code") != "OK"):
                            transient_statuses.add(response.status)
                            ready = False
                            break
                        profile = response.payload.get("data")
                        self.check(isinstance(profile, dict) and profile.get("id") == user_id,
                                   label + ".original-identity-matches")
            if ready:
                elapsed = time.monotonic() - started
                self.fault_observations.append({"operation": label, "original_credentials_ready": True,
                                                "seconds": round(elapsed, 3), "attempts": attempts,
                                                "transient_http_statuses": sorted(transient_statuses)})
                self.check(True, label + ".original-credentials-ready", recovery_seconds=round(elapsed, 3))
                return
            time.sleep(max(0.0, min(0.25, deadline - time.monotonic())))
        self.fault_observations.append({"operation": label, "original_credentials_ready": False,
                                        "seconds": round(time.monotonic() - started, 3), "attempts": attempts,
                                        "transient_http_statuses": sorted(transient_statuses)})
        self.check(False, label + ".original-credentials-ready-within-thirty-seconds")

    def restart_case(self) -> None:
        user, first = self.new_user(self.a, "F01")
        second = self.login(user, self.b, "F01.second-device")
        self.unexpired(self.control_device, "F01.control")
        self.expected(self.request(self.b, "POST", "/auth/logout-all", cookies=first.cookies), 200, "F01.revoke")
        self.revoked_devices = [first, second]
        self.rejected_devices(self.revoked_devices, "F01.before-restart")

        self.service_action("restart_a")

        self.control_is_unchanged("F01")
        self.valid_device(self.login(self.control, self.a, "F01.control-new-login"), "F01.control-account")
        self.rejected_devices(self.revoked_devices, "F01.after-restart")

    def open_sockets(self, user: Any, first: Any, second: Any, target: Any, label: str) -> tuple[Any, list[Any]]:
        if importlib.util.find_spec("websocket") is None:
            raise _base.CheckFailure("WS dependency unavailable: websocket-client is required")
        import websocket
        websocket.enableTrace(False)
        logging.getLogger("websocket").setLevel(logging.CRITICAL)
        connections: list[Any] = []
        try:
            with self.direct_websocket_environment():
                connection = websocket.create_connection(target.websocket_url, timeout=2,
                                                          cookie=first.cookies.header(), origin=self.ws_origin)
                connections.append(connection)
                self.ws_ready(connection, websocket, label + ".cookie-ready", user.user_id)
                connection = websocket.create_connection(target.websocket_url, timeout=2, origin=self.ws_origin)
                connections.append(connection)
                connection.send(json.dumps({"type": "auth", "token": second.access}))
                self.ws_ready(connection, websocket, label + ".bearer-ready", user.user_id)
            return websocket, connections
        except Exception:
            self.close_sockets(connections)
            raise

    @staticmethod
    def close_sockets(connections: list[Any]) -> None:
        for connection in connections:
            try:
                connection.close(timeout=0.1)
            except Exception:
                pass

    def private_event(self, connection: Any, websocket: Any, conversation_id: int, label: str,
                      expected_seq: int | None = None) -> int:
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                value = json.loads(connection.recv())
            except websocket.WebSocketTimeoutException:
                continue
            except Exception:
                raise _base.CheckFailure(label + ": private baseline event missing") from None
            if isinstance(value, dict) and value.get("type") == "event" and value.get("event") == "message":
                payload = value.get("payload")
                if isinstance(payload, dict) and payload.get("conversationId") == conversation_id:
                    seq = value.get("seq")
                    if expected_seq is not None and seq != expected_seq:
                        continue
                    self.check(isinstance(seq, int) and seq > 0, label)
                    return seq
        raise _base.CheckFailure(label + ": private baseline event deadline exceeded")

    def watch_closure(self, connections: list[Any], websocket: Any) -> tuple[float, list[Any], list[dict[str, Any]]]:
        started = time.monotonic()
        observations: list[dict[str, Any]] = [{} for _ in connections]
        readers = [threading.Thread(target=self.ws_observe_revoked,
                                    args=(connection, websocket, started, observations[index]), daemon=True)
                   for index, connection in enumerate(connections)]
        for reader in readers:
            reader.start()
        return started, readers, observations

    def assert_closed(self, watch: tuple[float, list[Any], list[dict[str, Any]]], label: str) -> None:
        started, readers, observations = watch
        for reader in readers:
            reader.join(max(0.0, started + 5.25 - time.monotonic()))
        for index, observation in enumerate(observations, 1):
            self.check(not observation.get("private_event", False), label + ".no-private-event" + str(index))
            elapsed = observation.get("elapsed", 99)
            self.check(observation.get("closed", False) and elapsed <= 5,
                       label + ".closed-within-five-seconds" + str(index), close_seconds=round(elapsed, 3))

    def mysql_case(self) -> None:
        self.check(bool(self.revoked_devices), "F02.previously-revoked-fixture-present")
        user, first = self.new_user(self.a, "F02")
        second = self.login(user, self.b, "F02.second-device")
        self.unexpired(first, "F02.first")
        self.unexpired(second, "F02.second")
        self.control_is_unchanged("F02.before")
        conversation = self.expected(self.request(self.a, "POST", "/messages/conversations",
                                                   bearer=self.control_device.access, body={"peerUid": user.public_uid}),
                                     200, "F02.conversation")
        conversation_id = int(conversation["id"])
        websocket, connections = self.open_sockets(user, first, second, self.b, "F02.before")
        replay_after = 0
        replay_expected = 0
        try:
            for number in (1, 2):
                self.expected(self.request(self.a, "POST", "/messages/conversations/" + str(conversation_id) + "/messages",
                                           bearer=self.control_device.access, body={"content": "DP02 private replay baseline"}),
                              200, "F02.baseline-message" + str(number))
                for index, connection in enumerate(connections, 1):
                    seq = self.private_event(connection, websocket, conversation_id,
                                             "F02.baseline-event" + str(number) + ".socket" + str(index))
                    if number == 1:
                        replay_after = seq
                    else:
                        replay_expected = seq
            self.check(replay_expected > replay_after, "F02.two-private-events-available")
            for index, connection in enumerate(connections, 1):
                connection.send(json.dumps({"type": "resume", "lastEventId": replay_after}))
                self.private_event(connection, websocket, conversation_id,
                                   "F02.healthy-replay-positive-control" + str(index), expected_seq=replay_expected)
            with self.outage("mysql"):
                watch = self.watch_closure(connections, websocket)
                # A retained private event exists in Redis; resume must not bypass unknown account state.
                try:
                    connections[1].send(json.dumps({"type": "resume", "lastEventId": replay_after}))
                except Exception:
                    pass  # An already-closed socket is verified by its reader below.
                try:
                    for target in (self.a, self.b):
                        for carrier, arguments in (("bearer", {"bearer": second.access}), ("cookie", {"cookies": first.cookies})):
                            response = self.request(target, "GET", "/messages/conversations", **arguments)
                            self.denied_without_private_data(response, "F02." + target.label + "." + carrier)
                finally:
                    self.assert_closed(watch, "F02.mysql-unavailable")
        finally:
            self.close_sockets(connections)

        self.valid_device(first, "F02.recovered-first")
        self.valid_device(second, "F02.recovered-second")
        self.control_is_unchanged("F02.recovered")
        self.rejected_devices(self.revoked_devices, "F02.previously-revoked-stay-revoked")
        websocket, reconnected = self.open_sockets(user, first, second, self.a, "F02.reconnected")
        try:
            self.expected(self.request(self.b, "POST", "/messages/conversations/" + str(conversation_id) + "/messages",
                                       bearer=self.control_device.access, body={"content": "DP02 private event after database recovery"}),
                          200, "F02.recovered-message")
            for index, connection in enumerate(reconnected, 1):
                self.private_event(connection, websocket, conversation_id, "F02.recovered-event" + str(index))
        finally:
            self.close_sockets(reconnected)

    def redis_case(self) -> None:
        self.check(bool(self.revoked_devices), "F03.previously-revoked-fixture-present")
        user, first = self.new_user(self.b, "F03.device-logout")
        second = self.login(user, self.a, "F03.device-other")
        cookie_user, cookie_first = self.new_user(self.a, "F03.cookie-device-logout")
        cookie_second = self.login(cookie_user, self.b, "F03.cookie-device-other")
        all_user, all_first = self.new_user(self.a, "F03.logout-all")
        all_second = self.login(all_user, self.b, "F03.logout-all-second")
        for index, device in enumerate((first, second, cookie_first, cookie_second,
                                       all_first, all_second, self.control_device), 1):
            self.unexpired(device, "F03.device" + str(index))
        single_logout_status = None
        cookie_logout_status = None
        with self.outage("redis"):
            for target in (self.a, self.b):
                self.denied_without_private_data(self.request(target, "GET", "/auth/me", bearer=second.access),
                                                 "F03." + target.label + ".access")
                self.denied_without_private_data(self.request(target, "GET", "/auth/me", cookies=self.control_device.cookies),
                                                 "F03." + target.label + ".cookie")
                self.denied_without_private_data(self.request(target, "POST", "/auth/refresh",
                                                              body={"refreshToken": second.refresh}),
                                                 "F03." + target.label + ".refresh")
                self.denied_without_private_data(self.request(target, "POST", "/auth/logout-all", bearer=all_first.access),
                                                 "F03." + target.label + ".logout-all")
            cookie_response = self.request(self.a, "POST", "/auth/logout", cookies=cookie_first.cookies)
            cookie_logout_status = cookie_response.status
            self.check(cookie_response.status != 200, "F03.cookie-only-logout-must-not-report-success",
                       actual_http=cookie_response.status)
            self.denied_without_private_data(cookie_response, "F03.cookie-only-logout-explicit-failure")
            self.fault_observations.append({"operation": "cookie-only-logout-during-redis-outage",
                                            "actual_http": cookie_response.status,
                                            "authorization_sent": False, "body_sent": False, "csrf_sent": True})
            response = self.request(self.b, "POST", "/auth/logout", bearer=first.access,
                                     body={"refreshToken": first.refresh})
            single_logout_status = response.status
            self.fault_observations.append({"operation": "single-device-logout-during-redis-outage",
                                            "actual_http": response.status})
            self.check(response.status in {200, 401, 500}, "F03.single-device-logout-observed", actual_http=response.status)
            if response.status == 200:
                self.expected(response, 200, "F03.single-device-logout-success")
            else:
                self.denied_without_private_data(response, "F03.single-device-logout-error")

        # A 401/500 during outage is an explicit non-success. Only the post-recovery
        # retry can prove durable device-scoped revocation.
        self.await_original_devices([(second, user.user_id), (self.control_device, self.control.user_id),
                                     (cookie_first, cookie_user.user_id), (cookie_second, cookie_user.user_id)],
                                    "F03.redis-connections-recovered")
        self.valid_device(second, "F03.other-device-preserved")
        self.control_is_unchanged("F03.unaffected-control")
        self.valid_device(cookie_first, "F03.failed-cookie-logout-left-original-device-valid")
        self.valid_device(cookie_second, "F03.failed-cookie-logout-left-other-device-valid")
        cookie_retry = self.request(self.b, "POST", "/auth/logout", cookies=cookie_first.cookies)
        self.expected(cookie_retry, 200, "F03.cookie-only-logout-retry-after-recovery")
        self.rejected_devices([cookie_first], "F03.cookie-only-retry-durable-device-revocation")
        self.valid_device(cookie_second, "F03.cookie-only-retry-preserved-other-device")
        self.control_is_unchanged("F03.cookie-only-retry-preserved-control")
        self.fault_observations.append({"operation": "cookie-only-logout-retry-after-recovery",
                                        "original_http": cookie_logout_status, "retry_http": cookie_retry.status,
                                        "original_cookie_preserved_before_retry": True, "confirmed_revoked": True})
        if single_logout_status != 200:
            retry = self.request(self.b, "POST", "/auth/logout",
                                 bearer=first.access, body={"refreshToken": first.refresh})
            self.expected(retry, 200, "F03.single-device-logout-retry-after-recovery")
            self.fault_observations.append({"operation": "single-device-logout-durable-after-recovery",
                                            "confirmed_revoked": True, "original_http": single_logout_status,
                                            "retry_http": retry.status})
        else:
            self.fault_observations.append({"operation": "single-device-logout-durable-after-recovery",
                                            "confirmed_revoked": True, "original_http": single_logout_status,
                                            "retry_http": None})
        self.rejected_devices([first], "F03.single-device-revoke-durable")
        self.expected(self.request(self.a, "POST", "/auth/heartbeat", cookies=self.control_device.cookies),
                      200, "F03.existing-cookie-and-csrf-recovered")

        state = self.request(self.b, "GET", "/auth/me", bearer=all_first.access)
        self.check(state.status in {200, 401}, "F03.logout-all-state-after-error", actual_http=state.status)
        retry_device = all_first
        all_snapshots = [all_first, all_second]
        if state.status == 200:
            self.valid_device(all_first, "F03.failed-action-left-first-valid")
            self.valid_device(all_second, "F03.failed-action-left-second-valid")
        else:
            self.rejected_devices(all_snapshots, "F03.failed-action-committed-revocation")
            retry_device = self.login(all_user, self.a, "F03.reauthenticate-after-committed-revocation")
            all_snapshots.append(retry_device)
        self.expected(self.request(self.a, "POST", "/auth/logout-all", cookies=retry_device.cookies),
                      200, "F03.logout-all-retry")
        self.rejected_devices(all_snapshots, "F03.retry-revoked-all-cookie-and-token-identities")
        self.rejected_devices(self.revoked_devices, "F03.previously-revoked-stay-revoked")
        self.control_is_unchanged("F03.after-retry")
        self.refresh_device(second, self.b, "F03.surviving-device-refresh")
        self.valid_device(second, "F03.new-cookie-session-after-recovery")

    def save_results(self) -> None:
        self.write_json("identity-fault-results.json", {
            "run": self.run_id,
            "targets": ["owned-loopback-A", "owned-loopback-B"],
            "cases": self.cases,
            "checks": self.checks,
            "control_actions": self.actions,
            "fault_observations": self.fault_observations,
            "recovery_still_required": sorted(self.recovery_required),
            "not_covered": self.uncovered,
            "credentials_recorded": False,
        })

    def run(self) -> int:
        self.control, self.control_device = self.new_user(self.b, "FAULT.control")
        try:
            for label, operation in (("F01-revocation-survives-application-restart", self.restart_case),
                                     ("F02-mysql-unknown-denies-private-data-and-replay", self.mysql_case),
                                     ("F03-redis-outage-has-no-false-revocation-success", self.redis_case)):
                if self.recovery_required:
                    self.cases.append({"case": label, "status": "NOT_RUN", "reason": "A preceding owned service did not recover"})
                    self.save_results()
                    continue
                self.run_case(label, operation)
        finally:
            for service in sorted(self.recovery_required):
                try:
                    self.service_action("start_" + service)
                    self.recovery_required.discard(service)
                except Exception:
                    pass
            self.save_results()
        failures = sum(case["status"] != "PASS" for case in self.cases)
        print("DP02 fault cases: " + str(len(self.cases) - failures) + " PASS, " + str(failures) + " FAIL", flush=True)
        return int(failures != 0 or bool(self.recovery_required))


def main() -> int:
    suite = None
    try:
        suite = FaultSuite()
        return suite.run()
    except _base.CheckFailure as failure:
        print("FAIL " + str(failure), flush=True)
    except Exception as failure:
        print("FAIL fault setup (" + type(failure).__name__ + ")", flush=True)
    if suite is not None:
        suite.cases.append({"case": "SETUP", "status": "FAIL", "reason": "Fault fixture or control identity unavailable"})
        suite.save_results()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
