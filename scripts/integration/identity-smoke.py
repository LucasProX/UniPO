#!/usr/bin/env python3
"""Own one temporary MinIO and run the unchanged, upload-enabled 42-check smoke."""

from __future__ import annotations

import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
from typing import Any
from urllib.parse import urlsplit


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("dp02_smoke_http_helpers", Path(__file__).with_name("identity-http.py"))
if SPEC is None or SPEC.loader is None:
    raise SystemExit("FAIL smoke helper is unavailable")
BASE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = BASE
SPEC.loader.exec_module(BASE)

EXPECTED_CHECKS = (
    "verification-code", "register", "me", "sms-code", "sms-login", "refresh", "refresh-me",
    "refresh-rejects-access", "create-post", "comment", "like", "favorite", "check-in", "stats",
    "push-token", "achievements", "achievement-complete", "hot-today", "dont-miss", "follow",
    "conversation", "send-message", "conversations", "unread-count", "upload-media", "v1-posts",
    "v1-boards", "v1-hot-today", "v1-dont-miss", "v1-me", "v1-page-1", "v1-page-walk",
    "v1-page-bad-cursor", "envelope-success", "envelope-error", "envelope-401", "time-offset",
    "register-minimal", "register-minimal-login", "register-duplicate", "register-nocode", "register-longpassword",
)
OWNER_FORMAT = (
    '{"id":{{json .Id}},"fixture":{{json (index .Config.Labels "com.unipo.fixture")}},'
    '"run":{{json (index .Config.Labels "com.unipo.fixture.run")}},'
    '"owner":{{json (index .Config.Labels "com.unipo.fixture.owner")}},"image":{{json .Image}},'
    '"running":{{json .State.Running}},"ports":{{json .HostConfig.PortBindings}}}'
)
SCHEDULE_SQL = {
    "ownership": "SELECT DATABASE(), LOWER(HEX(run_id)) FROM dp02_fixture_marker",
    "counts": """
        SELECT COUNT(*), COALESCE(SUM(intent_count), 0), COALESCE(SUM(delivered_count), 0),
               COALESCE(SUM(pending_count), 0), COALESCE(SUM(sending_count), 0),
               COALESCE(SUM(failed_count), 0), COALESCE(SUM(suppressed_count), 0),
               COALESCE(SUM(CASE WHEN intent_count = 2 AND delivered_count = 2
                                 AND realtime_count = 1 AND push_count = 1 THEN 0 ELSE 1 END), 0)
        FROM (
            SELECT m.id, COUNT(o.id) AS intent_count,
                   SUM(o.state = 'DELIVERED') AS delivered_count,
                   SUM(o.state = 'PENDING') AS pending_count,
                   SUM(o.state = 'SENDING') AS sending_count,
                   SUM(o.state = 'FAILED') AS failed_count,
                   SUM(o.state = 'SUPPRESSED') AS suppressed_count,
                   SUM(o.channel = 'REALTIME') AS realtime_count,
                   SUM(o.channel = 'PUSH') AS push_count
            FROM messages m LEFT JOIN message_delivery_outbox o ON o.message_id = m.id
            WHERE m.receiver_id = {receiver_id}
            GROUP BY m.id
        ) AS delivery_counts
    """,
}
SCHEDULE_METRIC_TYPES = {
    "unipo_message_outbox_failed_total": "counter",
    "unipo_message_outbox_failed_current": "gauge",
    "unipo_message_outbox_pending": "gauge",
    "unipo_message_outbox_sending": "gauge",
    "unipo_message_outbox_oldest_age_seconds": "gauge",
    "unipo_message_outbox_delivered_total": "counter",
    "unipo_message_outbox_suppressed_total": "counter",
    "unipo_message_outbox_retried_total": "counter",
    "unipo_message_outbox_continued_total": "counter",
    "unipo_message_outbox_lease_lost_total": "counter",
}


class SmokeFixture:
    def __init__(self) -> None:
        if (os.environ.get("UNIPO_DP02_SMOKE") != "1" or os.environ.get("UNIPO_DP02_BASELINE") == "1"
                or os.environ.get("UNIPO_DP02_FAULT") == "1"):
            raise BASE.CheckFailure("SETUP: explicit isolated smoke mode is required")
        self.suite = BASE.Suite()
        self.docker_context = subprocess.check_output(["docker", "context", "show"], text=True).strip()
        docker_endpoint = subprocess.check_output(["docker", "context", "inspect", self.docker_context,
                                                   "--format", "{{.Endpoints.docker.Host}}"], text=True).strip()
        if not docker_endpoint.startswith("unix://"):
            raise BASE.CheckFailure("SETUP: MinIO fixture requires the local Docker Unix socket")
        for tool in ("docker", "bash", "curl", "jq"):
            if not shutil.which(tool):
                raise BASE.CheckFailure("SETUP: required smoke fixture tool is unavailable")
        required_fixture = {
            "UNIPO_DP02_MYSQL_HOST": "127.0.0.1", "UNIPO_DP02_MYSQL_PORT": "22306",
            "UNIPO_DP02_REDIS_HOST": "127.0.0.1", "UNIPO_DP02_REDIS_PORT": "26379",
        }
        if any(os.environ.get(key) != value for key, value in required_fixture.items()):
            raise BASE.CheckFailure("SETUP: smoke requires the owning isolated MySQL/Redis wrapper")
        self.run_marker = os.environ.get("UNIPO_DP02_MYSQL_RUN_ID", "")
        if not re.fullmatch(r"[0-9a-f]{16}", self.run_marker):
            raise BASE.CheckFailure("SETUP: fixture run marker is invalid")
        endpoint = urlsplit(os.environ.get("MINIO_ENDPOINT", ""))
        if (endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or endpoint.port != 29000
                or endpoint.username is not None or endpoint.password is not None
                or endpoint.query or endpoint.fragment or endpoint.path not in {"", "/"}):
            raise BASE.CheckFailure("SETUP: MinIO must use this run's loopback port 29000")
        self.root_user = os.environ.get("MINIO_ACCESS_KEY", "")
        self.root_password = os.environ.get("MINIO_SECRET_KEY", "")
        self.bucket = os.environ.get("MINIO_BUCKET", "")
        if len(self.root_user) < 3 or len(self.root_password) < 8 or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", self.bucket):
            raise BASE.CheckFailure("SETUP: this run's MinIO credentials or bucket are missing")
        self.image_name = os.environ.get("UNIPO_DP02_MINIO_IMAGE", "bitnamilegacy/minio:latest")
        if "minio" not in self.image_name.lower():
            raise BASE.CheckFailure("SETUP: only a locally cached MinIO image may be selected")
        self.name = "unipo-dp02-smoke-minio-" + self.run_marker
        self.owner_nonce = secrets.token_hex(16)
        self.container_id: str | None = None
        self.image_id: str | None = None
        self.creation_attempted = False
        self.cleanup_complete = False
        self.check_counts: dict[str, int] = {}
        self.failure_names: set[str] = set()
        self.skipped = False
        self.smoke_returncode: int | None = None
        self.schedule_peer_id: int | None = None
        schedule_mode = os.environ.get("UNIPO_DP14_SCHEDULE_SMOKE", "0")
        if schedule_mode not in {"0", "1"}:
            raise BASE.CheckFailure("SETUP: scheduled outbox observation must be explicitly enabled or disabled")
        self.scheduled_outbox: dict[str, bool | int] = {
            "enabled": schedule_mode == "1", "ownership_verified": False, "apns_disabled": False,
            "owned_peer_only": False,
            "messages": 0, "intents": 0, "delivered": 0, "pending": 0, "sending": 0,
            "failed": 0, "suppressed": 0, "incomplete_messages": 0, "poll_attempts": 0,
            "all_messages_delivered": False, "metrics_a_ok": False, "metrics_b_ok": False,
            "complete": False,
        }

    def docker(self, arguments: list[str], *, env: dict[str, str] | None = None, check: bool = True) -> subprocess.CompletedProcess:
        result = subprocess.run(["docker", "--context", self.docker_context, *arguments], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, timeout=60)
        if check and result.returncode != 0:
            raise BASE.CheckFailure("MINIO: an owned Docker operation failed")
        return result

    def inspect_owner(self, reference: str) -> dict[str, Any] | None:
        result = self.docker(["container", "inspect", "--format", OWNER_FORMAT, reference], check=False)
        if result.returncode != 0:
            return None
        try:
            return json.loads(result.stdout)
        except ValueError:
            raise BASE.CheckFailure("MINIO: ownership metadata could not be verified") from None

    def owned(self, reference: str, *, require_running: bool = False) -> dict[str, Any]:
        info = self.inspect_owner(reference)
        expected_ports = {"9000/tcp": [{"HostIp": "127.0.0.1", "HostPort": "29000"}]}
        if (info is None or info.get("fixture") != "dp02-identity-smoke" or info.get("run") != self.run_marker
                or info.get("owner") != self.owner_nonce
                or info.get("image") != self.image_id or info.get("ports") != expected_ports
                or not re.fullmatch(r"[0-9a-f]{64}", str(info.get("id", "")))
                or require_running and info.get("running") is not True):
            raise BASE.CheckFailure("MINIO: refusing an object without this run's ownership and binding")
        return info

    def start_minio(self) -> None:
        image_format = '{"id":{{json .Id}},"user":{{json .Config.User}},"os":{{json .Os}},"entrypoint":{{json .Config.Entrypoint}}}'
        image = self.docker(["image", "inspect", self.image_name, "--format", image_format], check=False)
        if image.returncode != 0:
            raise BASE.CheckFailure("MINIO: the selected image is not cached; this fixture will not pull it")
        try:
            metadata = json.loads(image.stdout)
        except ValueError:
            raise BASE.CheckFailure("MINIO: cached image metadata is invalid") from None
        self.image_id = metadata.get("id")
        if not isinstance(self.image_id, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", self.image_id):
            raise BASE.CheckFailure("MINIO: cached image ID is invalid")
        user = str(metadata.get("user") or "0").split(":", 1)[0]
        if not user.isdigit() or metadata.get("os") != "linux":
            raise BASE.CheckFailure("MINIO: cached image has an unsupported fixture user")
        if metadata.get("entrypoint") != ["/opt/bitnami/scripts/minio/entrypoint.sh"]:
            raise BASE.CheckFailure("MINIO: the cached image does not use the supported temporary data layout")
        if self.inspect_owner(self.name) is not None:
            raise BASE.CheckFailure("MINIO: refusing to reuse an existing container name")
        # bind() tests ownership without contacting any pre-existing MinIO service.
        guard = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            guard.bind(("127.0.0.1", 29000))
            environment = os.environ.copy()
            environment.update({"MINIO_ROOT_USER": self.root_user, "MINIO_ROOT_PASSWORD": self.root_password,
                                "MINIO_DEFAULT_BUCKETS": self.bucket, "MINIO_BROWSER": "off"})
            self.creation_attempted = True
            created = self.docker([
                "create", "--pull=never", "--name", self.name,
                "--label", "com.unipo.fixture=dp02-identity-smoke", "--label", "com.unipo.fixture.run=" + self.run_marker,
                "--label", "com.unipo.fixture.owner=" + self.owner_nonce,
                "--log-driver=none", "--publish", "127.0.0.1:29000:9000",
                "--tmpfs", "/bitnami/minio/data:rw,uid=" + user + ",gid=0,mode=0700,size=1g",
                "--env", "MINIO_ROOT_USER", "--env", "MINIO_ROOT_PASSWORD",
                "--env", "MINIO_DEFAULT_BUCKETS", "--env", "MINIO_BROWSER", self.image_id,
            ], env=environment)
            candidate = created.stdout.strip()
            if not re.fullmatch(r"[0-9a-f]{64}", candidate):
                raise BASE.CheckFailure("MINIO: Docker did not return an owned container ID")
            self.container_id = candidate
            self.owned(self.container_id)
        except OSError:
            raise BASE.CheckFailure("MINIO: loopback port 29000 is occupied; existing services are never reused") from None
        finally:
            guard.close()
        self.docker(["start", self.container_id])
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            self.owned(self.container_id, require_running=True)
            connection = http.client.HTTPConnection("127.0.0.1", 29000, timeout=1)
            try:
                connection.request("GET", "/minio/health/live")
                response = connection.getresponse()
                response.read(1024)
                if response.status == 200:
                    return
            except OSError:
                pass
            finally:
                connection.close()
            time.sleep(0.5)
        raise BASE.CheckFailure("MINIO: the owned temporary instance did not become ready")

    def run_smoke(self) -> None:
        peer, _ = self.suite.new_user(self.suite.a, "SMOKE.peer")
        self.schedule_peer_id = peer.user_id
        self.suite.login(peer, self.suite.a, "SMOKE.peer-login")
        environment = os.environ.copy()
        environment["ADMIN_UID"] = peer.public_uid
        environment["SMOKE_SKIP_UPLOAD"] = "0"
        # curl in the original script must connect directly to the fixture, regardless of desktop proxies.
        for name in BASE.PROXY_VARIABLES:
            environment.pop(name, None)
        environment["NO_PROXY"] = environment["no_proxy"] = "127.0.0.1,localhost,::1"
        process = subprocess.Popen(["bash", str(ROOT / "scripts/smoke.sh"), "http://127.0.0.1:28080"],
                                   env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, start_new_session=True)
        try:
            try:
                output, _errors = process.communicate(timeout=180)
            except subprocess.TimeoutExpired:
                raise BASE.CheckFailure("SMOKE: the bounded original smoke run timed out") from None
        finally:
            if process.poll() is None:
                # This exact Popen owns a new process group; no pre-existing process is selected.
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.communicate(timeout=5)
        self.smoke_returncode = process.returncode
        # Discard all dynamic output, including random account names and response snippets.
        # Persist only names from the fixed 42-check allowlist.
        for line in output.splitlines():
            passed = re.match(r"^PASS\s+([a-z0-9-]+)(?:\s|$)", line)
            failed = re.match(r"^FAIL\s+([a-z0-9-]+)(?:\s|$)", line)
            if passed and passed.group(1) in EXPECTED_CHECKS:
                name = passed.group(1)
                self.check_counts[name] = self.check_counts.get(name, 0) + 1
            elif failed:
                self.failure_names.add(failed.group(1) if failed.group(1) in EXPECTED_CHECKS else "unrecognized-check")
            elif line.startswith(("SKIP", "ABORT")):
                self.skipped = True
        if (process.returncode != 0 or self.skipped or self.failure_names
                or set(self.check_counts) != set(EXPECTED_CHECKS)
                or any(count != 1 for count in self.check_counts.values())):
            raise BASE.CheckFailure("SMOKE: all 42 checks including upload must pass exactly once without skips")

    def schedule_mysql(self, query_name: str, timeout: float) -> str:
        # Every query is a fixed SELECT. The generated password is passed only in the child's environment.
        if query_name not in SCHEDULE_SQL:
            raise BASE.CheckFailure("SCHEDULE: unknown read-only fixture observation")
        query = SCHEDULE_SQL[query_name]
        if query_name == "counts":
            if type(self.schedule_peer_id) is not int or self.schedule_peer_id <= 0:
                raise BASE.CheckFailure("SCHEDULE: the owned smoke peer scope is invalid")
            # Only the positive numeric ID from this run's newly created peer fills this fixed SELECT.
            query = query.format(receiver_id=self.schedule_peer_id)
        environment = os.environ.copy()
        environment["MYSQL_PWD"] = environment["UNIPO_DP02_MYSQL_PASSWORD"]
        try:
            result = subprocess.run([
                "mysql", "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=22306",
                "--user=unipo_fixture", "--database=" + environment["UNIPO_DP02_MYSQL_SCHEMA"],
                "--connect-timeout=2", "--batch", "--raw", "--skip-column-names",
                "--execute=" + query,
            ], env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired):
            raise BASE.CheckFailure("SCHEDULE: the bounded fixture SELECT could not complete") from None
        if result.returncode != 0:
            raise BASE.CheckFailure("SCHEDULE: the read-only fixture SELECT failed")
        return result.stdout.strip()

    def observe_schedule_metrics(self, port: int) -> None:
        if port not in {28080, 28081}:
            raise BASE.CheckFailure("SCHEDULE: metric observation must use an owned loopback application")
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        try:
            connection.request("GET", "/actuator/prometheus")
            response = connection.getresponse()
            raw = response.read(BASE.MAX_RESPONSE_BYTES + 1)
            if response.status != 200 or len(raw) > BASE.MAX_RESPONSE_BYTES:
                raise BASE.CheckFailure("SCHEDULE: Prometheus must be bounded and return HTTP 200")
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                raise BASE.CheckFailure("SCHEDULE: Prometheus response encoding is invalid") from None
        except (OSError, http.client.HTTPException):
            raise BASE.CheckFailure("SCHEDULE: the loopback Prometheus observation failed") from None
        finally:
            connection.close()

        # Retain only booleans. Raw JVM/HTTP/provider metric samples are never saved to artifacts.
        families: dict[str, str] = {}
        for name, kind in re.findall(r"^# TYPE ([a-zA-Z_:][a-zA-Z0-9_:]*) ([a-zA-Z]+)\s*$", text, re.MULTILINE):
            if name in families:
                raise BASE.CheckFailure("SCHEDULE: Prometheus contains a duplicate TYPE family")
            families[name] = kind
        for name, kind in SCHEDULE_METRIC_TYPES.items():
            if families.get(name) != kind or not re.search(
                    r"^" + re.escape(name) + r"(?:\{[^\n]*\})?\s+\S+", text, re.MULTILINE):
                raise BASE.CheckFailure("SCHEDULE: an expected Outbox metric name or type is missing")

    def run_scheduled_outbox(self) -> None:
        if not self.scheduled_outbox["enabled"]:
            return
        if not shutil.which("mysql"):
            raise BASE.CheckFailure("SCHEDULE: the MySQL fixture client is unavailable")
        if (os.environ.get("UNIPO_DP02_MYSQL_HOST") != "127.0.0.1"
                or os.environ.get("UNIPO_DP02_MYSQL_PORT") != "22306"
                or os.environ.get("UNIPO_DP02_MYSQL_USER") != "unipo_fixture"
                or os.environ.get("UNIPO_DP02_MYSQL_SCHEMA") != "unipo_dp02_" + self.run_marker
                or not re.fullmatch(r"[0-9a-f]{48}", os.environ.get("UNIPO_DP02_MYSQL_PASSWORD", ""))):
            raise BASE.CheckFailure("SCHEDULE: refusing a database outside the owning fresh fixture")
        self.scheduled_outbox["apns_disabled"] = os.environ.get("APNS_ENABLED") == "false"
        if not self.scheduled_outbox["apns_disabled"]:
            raise BASE.CheckFailure("SCHEDULE: this observation requires the fixture's disabled APNs provider")
        owner = self.schedule_mysql("ownership", 3).split("\t")
        if owner != ["unipo_dp02_" + self.run_marker, self.run_marker]:
            raise BASE.CheckFailure("SCHEDULE: the database ownership marker did not match")
        self.scheduled_outbox["ownership_verified"] = True
        if type(self.schedule_peer_id) is not int or self.schedule_peer_id <= 0:
            raise BASE.CheckFailure("SCHEDULE: the original smoke's owned peer is unavailable")
        self.scheduled_outbox["owned_peer_only"] = True

        deadline = time.monotonic() + 20
        names = ("messages", "intents", "delivered", "pending", "sending", "failed", "suppressed", "incomplete_messages")
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            values = self.schedule_mysql("counts", min(3, remaining)).split("\t")
            if len(values) != len(names) or any(not re.fullmatch(r"[0-9]+", value) for value in values):
                raise BASE.CheckFailure("SCHEDULE: aggregate fixture counts are invalid")
            self.scheduled_outbox.update(zip(names, map(int, values)))
            self.scheduled_outbox["poll_attempts"] += 1
            if self.scheduled_outbox["failed"] or self.scheduled_outbox["suppressed"]:
                raise BASE.CheckFailure("SCHEDULE: an original smoke message was failed or suppressed")
            self.scheduled_outbox["all_messages_delivered"] = (
                self.scheduled_outbox["messages"] >= 1
                and self.scheduled_outbox["intents"] == self.scheduled_outbox["messages"] * 2
                and self.scheduled_outbox["delivered"] == self.scheduled_outbox["intents"]
                and not any(self.scheduled_outbox[name] for name in ("pending", "sending", "failed", "suppressed", "incomplete_messages"))
            )
            if self.scheduled_outbox["all_messages_delivered"]:
                break
            time.sleep(min(0.25, max(0, deadline - time.monotonic())))
        if not self.scheduled_outbox["all_messages_delivered"]:
            raise BASE.CheckFailure("SCHEDULE: original smoke messages did not finish both channels within twenty seconds")
        for port, key in ((28080, "metrics_a_ok"), (28081, "metrics_b_ok")):
            self.observe_schedule_metrics(port)
            self.scheduled_outbox[key] = True
        self.scheduled_outbox["complete"] = True

    def cleanup(self) -> None:
        if not self.creation_attempted:
            self.cleanup_complete = True
            return
        reference = self.container_id or self.name
        info = self.inspect_owner(reference)
        if info is None:
            self.cleanup_complete = True
            return
        verified = self.owned(reference)
        self.docker(["rm", "--force", "--volumes", verified["id"]])
        self.cleanup_complete = self.inspect_owner(verified["id"]) is None
        if not self.cleanup_complete:
            raise BASE.CheckFailure("MINIO: owned temporary instance cleanup did not complete")

    def save(self, status: str, reason: str | None) -> None:
        self.suite.write_json("identity-smoke-results.json", {
            "status": status, "expected_checks": 42, "passed_checks": len(self.check_counts),
            "checks": [{"check": name, "status": "PASS" if self.check_counts.get(name) == 1 else "FAIL"}
                       for name in EXPECTED_CHECKS],
            "failed_checks": sorted(self.failure_names), "skipped": self.skipped,
            "original_smoke_exit": self.smoke_returncode, "upload_enabled": True,
            "cached_image_id": self.image_id, "owned_container_id": self.container_id,
            "cleanup_complete": self.cleanup_complete, "credentials_recorded": False,
            "scheduled_outbox": self.scheduled_outbox,
            **({"reason": reason} if reason else {}),
        })
        summary = self.suite.artifacts / "identity-smoke-summary.txt"
        descriptor = os.open(summary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="ascii") as stream:
            for name in EXPECTED_CHECKS:
                if self.check_counts.get(name) == 1:
                    stream.write("PASS " + name + "\n")
        summary.chmod(0o600)


def interrupted(*_args: Any) -> None:
    raise SystemExit(143)


def main() -> int:
    fixture: SmokeFixture | None = None
    reason = None
    status = "FAIL"
    try:
        fixture = SmokeFixture()
        fixture.start_minio()
        fixture.run_smoke()
        fixture.run_scheduled_outbox()
        status = "PASS"
    except BASE.CheckFailure as failure:
        reason = str(failure)
    except Exception as failure:
        reason = "Fixture failure: " + type(failure).__name__
    finally:
        if fixture is not None:
            try:
                fixture.cleanup()
            except Exception as failure:
                status = "FAIL"
                reason = "Owned cleanup failed: " + type(failure).__name__
            fixture.save(status, reason)
    print(status + " smoke-42-with-upload (" + str(len(fixture.check_counts) if fixture else 0) + "/42)", flush=True)
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
