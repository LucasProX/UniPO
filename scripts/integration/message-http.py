#!/usr/bin/env python3
"""DP15 HTTP acceptance on two disposable loopback instances."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlencode


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "dp15_http_helpers", Path(__file__).with_name("identity-http.py"))
if SPEC is None or SPEC.loader is None:
    raise SystemExit("FAIL: HTTP fixture helpers are unavailable")
BASE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = BASE
SPEC.loader.exec_module(BASE)


class Acceptance:
    def __init__(self) -> None:
        self.suite = BASE.Suite()
        self.alice = None
        self.alice_device = None
        self.bob = None
        self.bob_device = None
        self.conversation_id = None
        self.other_conversation_id = None
        self.saved_ids: list[int] = []

    def response(self, target, method, path, *, body=None, device=None, status=200,
                 label: str, code: str | None = None):
        auth = {"bearer": device.access} if device else {}
        response = self.suite.request(target, method, path, body=body, **auth)
        data = self.suite.expected(response, status, label)
        if status != 200 and code:
            payload = response.payload if isinstance(response.payload, dict) else {}
            self.suite.check(payload.get("code") == code, label + ".code")
        return data

    def send(self, target, sender, conversation_id: int, text: str, key: str, label: str) -> dict:
        data = self.response(target, "POST", f"/messages/conversations/{conversation_id}/messages",
                             body={"content": text, "clientMessageId": key}, device=sender, label=label)
        self.suite.check(isinstance(data, dict) and isinstance(data.get("id"), int), label + ".message-id")
        return data

    def sql(self, statement: str) -> str:
        run = os.environ.get("UNIPO_DP02_MYSQL_RUN_ID", "")
        schema = os.environ.get("UNIPO_DP02_MYSQL_SCHEMA", "")
        if (os.environ.get("UNIPO_DP15_HTTP_INTEGRATION") != "1"
                or os.environ.get("UNIPO_DP02_MYSQL_HOST") != "127.0.0.1"
                or os.environ.get("UNIPO_DP02_MYSQL_PORT") != "22306"
                or not run or schema != "unipo_dp02_" + run
                or not all(ch in "0123456789abcdef" for ch in run) or len(run) != 16):
            raise BASE.CheckFailure("SETUP: SQL access requires this owned DP15 fixture")
        env = os.environ.copy()
        env["MYSQL_PWD"] = env.get("UNIPO_DP02_MYSQL_PASSWORD", "")
        marker = subprocess.run([
            "mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=22306",
            "--user=" + env["UNIPO_DP02_MYSQL_USER"], "--database=" + schema,
            "--batch", "--skip-column-names",
            "--execute=SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker",
        ], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=8)
        if marker.returncode != 0 or marker.stdout.strip() != run:
            raise BASE.CheckFailure("SETUP: refusing SQL access without the current MySQL ownership marker")
        result = subprocess.run([
            "mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=22306",
            "--user=" + env["UNIPO_DP02_MYSQL_USER"], "--database=" + schema,
            "--batch", "--skip-column-names", "--execute=" + statement,
        ], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=8)
        if result.returncode != 0:
            raise BASE.CheckFailure("FIXTURE SQL operation failed")
        return result.stdout.strip()

    def setup_users(self) -> None:
        self.alice, self.alice_device = self.suite.new_user(self.suite.a, "DP15.alice")
        self.bob, self.bob_device = self.suite.new_user(self.suite.b, "DP15.bob")

    def page(self, conversation_id: int, *, cursor: str | None = None,
             after_id: int | None = None, target=None, device=None, label: str):
        target = target or self.suite.a
        device = device or self.alice_device
        query = {"limit": "40"}
        if cursor is not None:
            query["cursor"] = cursor
        if after_id is not None:
            query["afterId"] = str(after_id)
        path = f"/messages/conversations/{conversation_id}/messages/page?{urlencode(query)}"
        return self.response(target, "GET", path, device=device, label=label)

    def run_messages(self) -> None:
        self.setup_users()
        created = self.response(self.suite.a, "POST", "/messages/conversations",
                                body={"peerUid": self.bob.public_uid}, device=self.alice_device,
                                label="DP15.conversation.create")
        self.conversation_id = int(created["id"])
        repeated = self.response(self.suite.b, "POST", "/messages/conversations",
                                 body={"peerUid": self.bob.public_uid}, device=self.alice_device,
                                 label="DP15.conversation.cross-instance-reuse")
        self.suite.check(int(repeated["id"]) == self.conversation_id, "DP15.conversation.same-id")
        exact = self.response(self.suite.b, "GET", f"/messages/conversations/{self.conversation_id}",
                              device=self.alice_device, label="DP15.conversation.exact")
        self.suite.check(int(exact["id"]) == self.conversation_id, "DP15.conversation.exact-id")

        intent = "dp15-" + self.suite.run_id + "-original"
        original = self.send(self.suite.a, self.alice_device, self.conversation_id,
                             "single saved message", intent, "DP15.intent.first")
        repeated = self.send(self.suite.b, self.alice_device, self.conversation_id,
                             "single saved message", intent, "DP15.intent.cross-instance-retry")
        self.suite.check(original["id"] == repeated["id"]
                         and original.get("clientMessageId") == repeated.get("clientMessageId") == intent,
                         "DP15.intent.same-message")
        self.response(self.suite.a, "POST", f"/messages/conversations/{self.conversation_id}/messages",
                      body={"content": "changed payload", "clientMessageId": intent}, device=self.alice_device,
                      status=409, label="DP15.intent.changed-payload", code="STATE_CONFLICT")

        # A second conversation uses a real third identity to verify key scope across threads.
        peer, peer_device = self.suite.new_user(self.suite.a, "DP15.other-peer")
        second = self.response(self.suite.a, "POST", "/messages/conversations",
                               body={"peerUid": peer.public_uid}, device=self.alice_device,
                               label="DP15.second-conversation")
        self.other_conversation_id = int(second["id"])
        self.response(self.suite.b, "POST", f"/messages/conversations/{self.other_conversation_id}/messages",
                      body={"content": "cross-thread retry", "clientMessageId": intent},
                      device=self.alice_device, status=409, label="DP15.intent.changed-conversation",
                      code="STATE_CONFLICT")
        del peer_device

        # The baseline is the last message Alice has already synchronized.
        baseline = self.send(self.suite.b, self.bob_device, self.conversation_id,
                             "known-before-disconnect", "dp15-" + self.suite.run_id + "-known",
                             "DP15.sync.baseline")
        missing: list[dict] = []
        for index in range(125):
            sender = self.bob_device
            target = self.suite.a if index % 2 == 0 else self.suite.b
            key = f"dp15-{self.suite.run_id}-bulk-{index}"
            missing.append(self.send(target, sender, self.conversation_id, f"missed-{index}", key,
                                     "DP15.sync.write"))
        actual: list[dict] = []
        page = self.page(self.conversation_id, after_id=int(baseline["id"]), label="DP15.sync.page-1")
        actual.extend(page["items"])
        self.suite.check(page["hasMore"] is True, "DP15.sync.multiple-pages")
        cursor = page["nextCursor"]
        current_page = 1
        while cursor:
            current_page += 1
            page = self.page(self.conversation_id, cursor=cursor,
                             target=self.suite.b if current_page % 2 == 0 else self.suite.a,
                             label=f"DP15.sync.page-{current_page}")
            actual.extend(page["items"])
            self.suite.check(len(actual) <= len(missing), "DP15.sync.bounded")
            cursor = page["nextCursor"]
        expected_ids = [item["id"] for item in missing]
        actual_ids = [item["id"] for item in actual]
        self.suite.check(actual_ids == expected_ids, "DP15.sync.no-gaps-or-duplicates",
                         received=len(actual_ids), expected=len(expected_ids))
        self.suite.check(all(item["receiverId"] == self.alice.user_id for item in actual),
                         "DP15.sync.receiver")
        self.response(
            self.suite.a, "GET",
            f"/messages/conversations/{self.conversation_id}/messages/page?afterId=-1",
            device=self.alice_device, status=400, label="DP15.sync.negative-position")
        self.response(
            self.suite.a, "GET",
            f"/messages/conversations/{self.conversation_id}/messages/page?afterId=1&cursor=bad",
            device=self.alice_device, status=400, label="DP15.sync.mixed-position")

        # History pages are newest-first and remain exclusive while new messages arrive.
        history = self.page(self.conversation_id, target=self.suite.b, label="DP15.history.page-1")
        collected = list(history["items"])
        history_cursor = history["nextCursor"]
        foreign_owner_cursor = history_cursor
        page_number = 1
        while history_cursor:
            page_number += 1
            history = self.page(self.conversation_id, cursor=history_cursor,
                                target=self.suite.a if page_number % 2 else self.suite.b,
                                label=f"DP15.history.page-{page_number}")
            collected.extend(history["items"])
            self.suite.check(len(collected) <= len(missing) + 2, "DP15.history.bounded")
            history_cursor = history["nextCursor"]
        self.suite.check([item["id"] for item in collected]
                         == sorted([original["id"], baseline["id"], *expected_ids], reverse=True),
                         "DP15.history.complete")
        self.suite.check(len({item["id"] for item in collected}) == len(collected),
                         "DP15.history.unique")
        # Cursors are opaque but scoped to both the authenticated owner and thread.
        if not foreign_owner_cursor:
            raise BASE.CheckFailure("DP15 history fixture did not produce an exclusive cursor")
        bob_foreign = self.response(
            self.suite.b, "GET",
            f"/messages/conversations/{self.conversation_id}/messages/page?{urlencode({'cursor': foreign_owner_cursor})}",
            device=self.bob_device, status=400, label="DP15.history.foreign-owner")
        del bob_foreign
        conversations = self.response(self.suite.b, "GET", "/messages/conversations/page?limit=20",
                                      device=self.alice_device, label="DP15.conversation.page")
        self.suite.check(any(item["id"] == self.conversation_id for item in conversations["items"]),
                         "DP15.conversation.page-contains-thread")
        self.suite.check("nextCursor" in conversations and "hasMore" in conversations,
                         "DP15.conversation.page-shape")
        self.saved_ids = [int(original["id"]), int(baseline["id"]), *map(int, expected_ids)]

    def run_outbox(self) -> None:
        if self.alice is None or self.alice_device is None or self.bob is None:
            raise BASE.CheckFailure("SETUP: message fixture identities are unavailable")
        admin = self.suite.admin(self.suite.a)
        message_id = self.saved_ids[0]
        changed = self.sql(
            "UPDATE message_delivery_outbox SET state='FAILED', attempts=1, last_error='DELIVERY_FAILED', "
            "available_at=UTC_TIMESTAMP(6), completed_at=UTC_TIMESTAMP(6), claim_token=NULL, lease_until=NULL "
            f"WHERE message_id={message_id} AND channel='REALTIME'")
        # MySQL command output has no rows for UPDATE; select only bounded fixture metadata afterwards.
        del changed
        row = self.sql(
            "SELECT id,event_id,attempts,state FROM message_delivery_outbox "
            f"WHERE message_id={message_id} AND channel='REALTIME'")
        fields = row.split("\t")
        if len(fields) != 4 or not fields[0].isdigit() or not fields[2].isdigit() or fields[3] != "FAILED":
            raise BASE.CheckFailure("FIXTURE SQL operation did not create one owned failed delivery")
        delivery_id, event_id, attempts = int(fields[0]), fields[1], int(fields[2])
        self.suite.check(bool(event_id) and attempts == 1, "DP15.outbox.fixture-failed")

        self.response(self.suite.a, "GET", "/admin/message-deliveries?state=FAILED&channel=REALTIME",
                      status=401, label="DP15.outbox.unauthenticated", code="AUTH_UNAUTHENTICATED")
        self.response(self.suite.a, "GET", "/admin/message-deliveries?state=FAILED&channel=REALTIME",
                      device=self.alice_device, status=403, label="DP15.outbox.user-denied", code="AUTH_FORBIDDEN")
        role = self.response(
            self.suite.a, "PUT", f"/admin/users/{self.bob.user_id}/role",
            body={"role": "OPERATOR", "operatorScope": "all"}, device=admin,
            label="DP15.outbox.promote-fixture-operator")
        del role
        operator = self.suite.login(self.bob, self.suite.b, "DP15.operator.login")
        self.response(self.suite.b, "GET", "/admin/message-deliveries?state=FAILED",
                      device=operator, status=403, label="DP15.outbox.operator-denied", code="AUTH_FORBIDDEN")
        self.response(self.suite.b, "POST", f"/admin/message-deliveries/{delivery_id}/retry",
                      body={"expectedAttempts": attempts, "reason": "fixture permission check"},
                      device=operator, status=403, label="DP15.outbox.operator-retry-denied", code="AUTH_FORBIDDEN")

        listed = self.response(
            self.suite.b, "GET", "/admin/message-deliveries?state=FAILED&channel=REALTIME&limit=10",
            device=admin, label="DP15.outbox.admin-list")
        items = listed.get("items", [])
        item = next((value for value in items if value.get("id") == delivery_id), None)
        self.suite.check(isinstance(item, dict), "DP15.outbox.failed-visible")
        allowed = {"id", "eventId", "messageId", "channel", "state", "attempts", "lastError",
                   "createdAt", "availableAt", "leaseUntil", "completedAt"}
        self.suite.check(set(item) <= allowed and "content" not in item and "token" not in json.dumps(item),
                         "DP15.outbox.metadata-only")
        self.suite.check(item.get("eventId") == event_id and item.get("attempts") == attempts,
                         "DP15.outbox.event-and-attempts")
        retried = self.response(
            self.suite.b, "POST", f"/admin/message-deliveries/{delivery_id}/retry",
            body={"expectedAttempts": attempts, "reason": "fixture permission check"},
            device=admin, label="DP15.outbox.admin-retry")
        self.suite.check(retried.get("state") == "PENDING" and retried.get("eventId") == event_id,
                         "DP15.outbox.retry-preserves-intent")
        self.response(
            self.suite.a, "POST", f"/admin/message-deliveries/{delivery_id}/retry",
            body={"expectedAttempts": attempts, "reason": "stale attempt"},
            device=admin, status=409, label="DP15.outbox.stale-retry", code="STATE_CONFLICT")

    def run(self) -> int:
        self.suite.run_case("OPENAPI", self.suite.export_openapi)
        self.suite.run_case("DP15-messages", self.run_messages)
        self.suite.run_case("DP15-outbox-operations", self.run_outbox)
        self.suite.write_json("message-http-results.json", {
            "run": self.suite.run_id,
            "targets": ["loopback-A", "loopback-B"],
            "cases": self.suite.cases,
            "checks": self.suite.checks,
            "credentials_recorded": False,
        })
        failed = sum(case["status"] != "PASS" for case in self.suite.cases)
        print(f"DP15 cases: {len(self.suite.cases) - failed} PASS, {failed} FAIL", flush=True)
        return int(failed != 0)


if __name__ == "__main__":
    try:
        raise SystemExit(Acceptance().run())
    except BASE.CheckFailure as error:
        print("FAIL DP15 HTTP setup: " + str(error), file=sys.stderr)
        raise SystemExit(2)
    except Exception as error:
        print("FAIL DP15 HTTP setup (" + type(error).__name__ + ")", file=sys.stderr)
        raise SystemExit(2)
