"""Pure CLI safety/format tests; no live API, credentials or database access."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("message_delivery_ops", Path(__file__).with_name("message-deliveries.py"))
assert spec is not None and spec.loader is not None
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


def record():
    return {"id": 2, "eventId": "0123456789abcdef0123456789abcdef", "messageId": 3,
            "channel": "PUSH", "state": "FAILED", "attempts": 4, "lastError": "APNS_REJECTED",
            "createdAt": "2026-10-22T01:02:03Z", "availableAt": "2026-10-22T01:02:03+00:00",
            "leaseUntil": None, "completedAt": "2026-10-22T01:02:04Z"}


class MessageDeliveryCliTests(unittest.TestCase):
    def test_remote_plain_http_and_embedded_credentials_are_rejected(self):
        for value in ("http://example.invalid/api/v1", "https://user:secret@example.invalid/api/v1",
                      "https://example.invalid/api/v1?token=secret", "https://example.invalid/api/v1\n"):
            with self.assertRaises(ops.OperationError):
                ops.base_url(value)
        self.assertTrue(ops.base_url("http://127.0.0.1:18081/api/v1")[1])
        self.assertTrue(ops.base_url("http://[::1]:8080/api/v1")[1])
        self.assertFalse(ops.base_url("https://example.invalid/api/v1")[1])

    def test_only_validated_metadata_can_be_printed(self):
        value = record()
        value.update({"content": "PRIVATE_BODY", "token": "DEVICE_CREDENTIAL", "claimToken": "CLAIM_SECRET"})
        result = ops.delivery(value)
        self.assertEqual(set(result), set(record()))
        self.assertNotIn("PRIVATE_BODY", str(result))
        self.assertNotIn("DEVICE_CREDENTIAL", str(result))
        for invalid in (dict(value, state=[]), dict(value, lastError="secret body text"),
                        dict(value, eventId="SECRET"), dict(value, createdAt=None)):
            with self.assertRaises(ops.OperationError) as caught:
                ops.delivery(invalid)
            self.assertNotIn("SECRET", str(caught.exception))

    def test_api_failures_do_not_echo_html_messages_or_credentials(self):
        self.assertEqual("API 请求失败 HTTP 502", str(ops.stable_failure(502, b"<html>PRIVATE_SECRET</html>")))
        failure = ops.stable_failure(409, b'{"code":"STATE_CONFLICT","message":"PRIVATE_SECRET", "requestId":"r-123"}')
        self.assertIn("STATE_CONFLICT", str(failure))
        self.assertIn("r-123", str(failure))
        self.assertNotIn("PRIVATE_SECRET", str(failure))

    def test_remote_retry_needs_explicit_target_flag_before_reading_credentials(self):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(sys, "argv", ["message-deliveries.py", "--base", "https://example.invalid/api/v1",
                                       "retry", "2", "--expected-attempts", "4", "--reason", "config fixed"]), \
                patch.object(ops, "credential", side_effect=AssertionError("must not read credentials")), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(2, ops.main())
        self.assertEqual("", output.getvalue())
        self.assertIn("--allow-remote-write", errors.getvalue())

    def test_cli_uses_env_credential_without_printing_it_or_unknown_response_fields(self):
        value = record()
        value["content"] = "PRIVATE_BODY"
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(sys, "argv", ["message-deliveries.py", "retry", "2", "--expected-attempts", "4",
                                       "--reason", "config fixed"]), \
                patch.dict(os.environ, {ops.TOKEN_ENV: "SYNTHETIC_CREDENTIAL"}), \
                patch.object(ops, "call", return_value=value) as request, \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(0, ops.main())
        self.assertEqual("SYNTHETIC_CREDENTIAL", request.call_args.args[1])
        self.assertEqual("POST", request.call_args.args[2])
        self.assertEqual({"expectedAttempts": 4, "reason": "config fixed"}, request.call_args.args[4])
        self.assertNotIn("SYNTHETIC_CREDENTIAL", output.getvalue())
        self.assertNotIn("PRIVATE_BODY", output.getvalue())
        self.assertEqual("", errors.getvalue())

    def test_redirects_and_invalid_page_shapes_are_not_accepted(self):
        self.assertIsNone(ops.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://other.invalid/"))
        for value in ({"items": [record()], "hasMore": False, "limit": 1, "nextCursor": "unexpected"},
                      {"items": [record(), record()], "hasMore": False, "limit": 1, "nextCursor": None}):
            with self.assertRaises(ops.OperationError):
                ops.page(value)


if __name__ == "__main__":
    unittest.main()
