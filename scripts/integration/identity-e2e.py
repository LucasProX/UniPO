#!/usr/bin/env python3
"""Run two loopback application processes inside newly owned MySQL/Redis lifecycles."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import socketserver
import subprocess
import sys
import time
import threading
import urllib.error
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = ROOT / "scripts" / "integration"


def baseline_mode():
    value = os.environ.get("UNIPO_DP02_BASELINE", "0")
    if value not in ("0", "1"):
        fail("The explicit baseline mode flag must be 0 or 1")
    return value == "1"


def client_script():
    name = ("identity-baseline.py" if baseline_mode() else "identity-fault.py" if fault_mode()
            else "identity-smoke.py" if smoke_mode() else "identity-http.py")
    return FIXTURE_DIR / name


def fault_mode():
    value = os.environ.get("UNIPO_DP02_FAULT", "0")
    if value not in ("0", "1") or value == "1" and baseline_mode():
        fail("Fault mode must be explicit and separate from the legacy baseline")
    return value == "1"


def smoke_mode():
    value = os.environ.get("UNIPO_DP02_SMOKE", "0")
    if value not in ("0", "1") or value == "1" and (baseline_mode() or fault_mode()):
        fail("Smoke mode must be explicit and separate from the identity modes")
    return value == "1"


def fail(message):
    raise RuntimeError(message)


def backend_jar():
    if baseline_mode():
        location = os.environ.get("UNIPO_DP02_BASELINE_JAR", "")
        if not location or not Path(location).is_file():
            fail("Explicit baseline mode requires the separately archived legacy jar")
        jar = Path(location).resolve()
        with zipfile.ZipFile(jar) as archive:
            if "BOOT-INF/classes/com/biecuoguo/BieCuoGuoApplication.class" not in archive.namelist():
                fail("The legacy fixture is not an executable backend package")
        return jar
    jar = Path(os.environ.get("UNIPO_DP02_BACKEND_JAR", str(ROOT / "backend/target/biecuoguo-backend-0.1.0.jar"))).resolve()
    if not jar.is_file():
        fail("Package the current backend first; no HTTP fixture was started")
    classes = ROOT / "backend/target/classes"
    if not classes.is_dir():
        fail("Current compiled classes are missing; package the implementation first")
    # Reproducible Boot jars deliberately have a 1980 mtime. Compare compiled bytes instead.
    with zipfile.ZipFile(jar) as archive:
        for compiled in classes.rglob("*"):
            if not compiled.is_file():
                continue
            name = "BOOT-INF/classes/" + compiled.relative_to(classes).as_posix()
            try:
                packaged = archive.read(name)
            except KeyError:
                fail("The jar does not contain the current compiled output; repackage first")
            if packaged != compiled.read_bytes():
                fail("The jar differs from current compiled output; repackage first")
        java_root = ROOT / "backend/src/main/java"
        for source in java_root.rglob("*.java"):
            compiled = classes / source.relative_to(java_root).with_suffix(".class")
            if not compiled.is_file() or source.stat().st_mtime_ns > compiled.stat().st_mtime_ns:
                fail("A Java source is newer than compiled output; rebuild and package first")
        resource_root = ROOT / "backend/src/main/resources"
        for resource in resource_root.rglob("*"):
            if resource.is_file():
                name = "BOOT-INF/classes/" + resource.relative_to(resource_root).as_posix()
                try:
                    packaged = archive.read(name)
                except KeyError:
                    fail("A current resource is missing from the jar; repackage first")
                if packaged != resource.read_bytes():
                    fail("A current resource differs from the jar; repackage first")
    return jar


def verify_fixtures(env):
    required = {
        "UNIPO_DP02_MYSQL_HOST": "127.0.0.1", "UNIPO_DP02_MYSQL_PORT": "22306",
        "UNIPO_DP02_REDIS_HOST": "127.0.0.1", "UNIPO_DP02_REDIS_PORT": "26379",
    }
    if any(env.get(key) != value for key, value in required.items()):
        fail("This runner requires both newly owned fixture lifecycles")
    mysql_run = env.get("UNIPO_DP02_MYSQL_RUN_ID", "")
    if len(mysql_run) != 16 or any(c not in "0123456789abcdef" for c in mysql_run):
        fail("Invalid MySQL fixture run marker")
    if env.get("UNIPO_DP02_MYSQL_SCHEMA") != "unipo_dp02_" + mysql_run:
        fail("Fixture schema does not match the current run")
    mysql_env = env.copy()
    mysql_env["MYSQL_PWD"] = env["UNIPO_DP02_MYSQL_PASSWORD"]
    marker = subprocess.run([
        "mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=22306",
        "--user=" + env["UNIPO_DP02_MYSQL_USER"], "--database=" + env["UNIPO_DP02_MYSQL_SCHEMA"],
        "--batch", "--skip-column-names", "--execute=SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker",
    ], env=mysql_env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()
    if marker != mysql_run:
        fail("MySQL fixture ownership marker did not match")
    redis_marker = env.get("UNIPO_DP02_RUN_ID", "").encode("ascii")
    with socket.create_connection(("127.0.0.1", 26379), timeout=2) as connection:
        connection.sendall(b"*2\r\n$3\r\nGET\r\n$25\r\nunipo:dp02:fixture:marker\r\n")
        response = connection.makefile("rb")
        if response.readline() != b"$16\r\n" or response.read(18) != redis_marker + b"\r\n":
            fail("Redis fixture ownership marker did not match")
    for port in (28080, 28081):
        probe = socket.socket()
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            fail("An HTTP fixture port is already occupied; refusing reuse")
        finally:
            probe.close()


def application_environment(env):
    result = env.copy()
    result.update({
        "SPRING_CONFIG_IMPORT": "", "SPRING_PROFILES_ACTIVE": "test",
        "SPRING_DATASOURCE_URL": "jdbc:mysql://127.0.0.1:22306/" + env["UNIPO_DP02_MYSQL_SCHEMA"]
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
            + "&useSSL=false&connectTimeout=2000&socketTimeout=5000",
        "SPRING_DATASOURCE_USERNAME": env["UNIPO_DP02_MYSQL_USER"],
        "SPRING_DATASOURCE_PASSWORD": env["UNIPO_DP02_MYSQL_PASSWORD"],
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "4",
        "SPRING_FLYWAY_BASELINE_ON_MIGRATE": "true", "SPRING_FLYWAY_BASELINE_VERSION": "0",
        "SPRING_JMX_ENABLED": "false", "STATE_BACKEND": "redis", "REDIS_HOST": "127.0.0.1",
        "REDIS_PORT": "26379", "REDIS_PASSWORD": "", "JWT_SECRET": secrets.token_hex(32),
        "JWT_EXPIRATION_MINUTES": "1", "JWT_REFRESH_EXPIRATION_MINUTES": "3",
        "AUTH_COOKIE_SECURE": "false", "AUTH_SESSION_TTL_MINUTES": "1",
        "RATE_LIMIT_ENABLED": "false", "RATE_LIMIT_TRUST_FORWARDED_FOR": "false",
        "BOOTSTRAP_SEED_DEMO_DATA": "false", "APNS_ENABLED": "false",
        "VERIFICATION_MOCK_ACCEPT_ANY_CODE": "true", "SMS_MOCK_ACCEPT_ANY_CODE": "true",
        "BOOTSTRAP_ADMIN_EMAIL": "dp02_admin_" + env["UNIPO_DP02_MYSQL_RUN_ID"] + "@example.invalid",
        "BOOTSTRAP_ADMIN_PASSWORD": secrets.token_hex(24),
        "APP_CORS_ALLOWED_ORIGINS": "http://127.0.0.1:5173,http://localhost",
        "APP_DEFAULT_SCHOOL_ID": "1", "APP_TIME_ZONE": "Asia/Shanghai",
        "MINIO_ENDPOINT": "http://127.0.0.1:29000", "MINIO_PUBLIC_ENDPOINT": "http://127.0.0.1:29000",
        "MINIO_ACCESS_KEY": secrets.token_hex(8), "MINIO_SECRET_KEY": secrets.token_hex(24),
        "MINIO_BUCKET": "dp02-" + env["UNIPO_DP02_MYSQL_RUN_ID"],
        "SMTP_HOST": "", "SMTP_USERNAME": "", "SMTP_PASSWORD": "",
        "UNIPO_DP02_HTTP_A": "http://127.0.0.1:28080/api/v1",
        "UNIPO_DP02_HTTP_B": "http://127.0.0.1:28081/api/v1",
        "UNIPO_DP02_WS_ORIGIN": "http://127.0.0.1:5173",
    })
    result["UNIPO_DP02_ADMIN_ACCOUNT"] = result["BOOTSTRAP_ADMIN_EMAIL"]
    result["UNIPO_DP02_ADMIN_PASSWORD"] = result["BOOTSTRAP_ADMIN_PASSWORD"]
    if fault_mode() or smoke_mode():
        result.update({"JWT_EXPIRATION_MINUTES": "5", "AUTH_SESSION_TTL_MINUTES": "5",
                       "JWT_REFRESH_EXPIRATION_MINUTES": "15",
                       "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT": "2000",
                       "SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT": "1000"})
    return result


def await_application(process, port, closing=None, dependencies=False):
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if closing is not None and closing.is_set():
            fail("The owned fixture is shutting down")
        if process.poll() is not None:
            fail("An owned application exited during startup; inspect this run's application log")
        try:
            path = "/actuator/health" if dependencies else "/actuator/health/readiness"
            with client.open("http://127.0.0.1:" + str(port) + path, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    fail("An owned application did not become ready; inspect this run's application log")


class FixtureControl:
    """Only fixed actions against this run's pinned containers and owned application handles."""
    ACTIONS = {"restart_a", "restart_b", "stop_mysql", "start_mysql", "stop_redis", "start_redis"}

    def __init__(self, artifacts, env, restart, applications, closing):
        self.artifacts, self.env, self.restart = artifacts, env, restart
        self.applications, self.closing = applications, closing
        self.phase = "idle"
        context = subprocess.check_output(["docker", "context", "show"], text=True).strip()
        endpoint = subprocess.check_output(["docker", "context", "inspect", context,
                                             "--format", "{{.Endpoints.docker.Host}}"], text=True).strip()
        if not endpoint.startswith("unix://"):
            fail("Fault injection requires the local Docker Unix socket")
        self.docker = ["docker", "--context", context]
        self.containers = {}
        for kind, name_key, run_key in (
                ("mysql", "UNIPO_DP02_MYSQL_CONTAINER", "UNIPO_DP02_MYSQL_RUN_ID"),
                ("redis", "UNIPO_DP02_REDIS_CONTAINER", "UNIPO_DP02_RUN_ID")):
            name, run = env[name_key], env[run_key]
            if name != "unipo-dp02-" + kind + "-" + run:
                fail("Unexpected fixture container name")
            identity, owner = self.inspect(name)
            if owner != run:
                fail("Fixture container run marker did not match")
            self.containers[kind] = (name, identity, run)
        self.path = artifacts / "control.sock"
        if len(os.fsencode(self.path)) >= 100:
            fail("The private control socket path exceeds the local Unix socket limit")
        owner = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.connection.settimeout(3)
                started = time.monotonic()
                action = "invalid"
                owner.phase = "request"
                try:
                    request = json.loads(self.rfile.readline(1024))
                    action = request.get("action")
                    if action not in owner.ACTIONS:
                        fail("Unsupported fixture action")
                    owner.dispatch(action)
                    response = {"ok": True, "phase": "complete", "seconds": round(time.monotonic() - started, 3)}
                except Exception:
                    response = {"ok": False, "error": "Owned fixture action failed",
                                "phase": owner.phase,
                                "seconds": round(time.monotonic() - started, 3)}
                with (owner.artifacts / "control-results.jsonl").open("a", encoding="utf8") as output:
                    output.write(json.dumps({"action": action if action in owner.ACTIONS else "invalid", **response}) + "\n")
                try:
                    self.wfile.write(json.dumps(response).encode("utf8") + b"\n")
                except OSError:
                    pass

        self.server = socketserver.UnixStreamServer(str(self.path), Handler)
        os.chmod(self.path, 0o600)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        self.thread.start()

    def inspect(self, name):
        result = subprocess.run(self.docker + ["container", "inspect", name, "--format",
                                '{{.Id}}|{{index .Config.Labels "com.unipo.fixture.run"}}'],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
        values = result.stdout.strip().split("|")
        if len(values) != 2:
            fail("Cannot verify the owned container")
        return values

    def assert_owner(self, kind):
        name, identity, run = self.containers[kind]
        if self.inspect(name) != [identity, run]:
            fail("Fixture container ownership changed")
        return identity

    def dispatch(self, action):
        self.phase = "precondition"
        if self.closing.is_set():
            fail("The fixture is shutting down")
        if action in ("restart_a", "restart_b"):
            self.phase = "restart_application"
            self.restart("a" if action == "restart_a" else "b")
            return
        verb, kind = action.split("_")
        self.phase = "verify_" + kind + "_ownership"
        identity = self.assert_owner(kind)
        self.phase = verb + "_" + kind + "_container"
        command = [verb, "--time", "2", identity] if verb == "stop" else [verb, identity]
        subprocess.run(self.docker + command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        if verb == "start":
            self.phase = kind + "_marker_recovery"
            self.await_service(kind, identity)
            for port, process in ((28080, self.applications["a"]), (28081, self.applications["b"])):
                self.phase = "application_" + str(port) + "_dependencies"
                await_application(process, port, self.closing, dependencies=True)

    def await_service(self, kind, identity):
        if kind == "mysql":
            command = ["sh", "-eu", "-c", 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP '
                       '--host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch '
                       '--skip-column-names --execute="SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker"']
            expected = self.env["UNIPO_DP02_MYSQL_RUN_ID"]
        else:
            command = ["redis-cli", "GET", "unipo:dp02:fixture:marker"]
            expected = self.env["UNIPO_DP02_RUN_ID"]
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.closing.is_set():
                fail("The fixture is shutting down")
            self.assert_owner(kind)
            result = subprocess.run(self.docker + ["exec", identity] + command,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
            if result.returncode == 0 and result.stdout.strip() == expected:
                return
            time.sleep(0.5)
        fail("The owned service did not recover its run marker")

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.path.unlink(missing_ok=True)


def run_http(jar):
    for tool in ("java", "mysql"):
        if not shutil.which(tool):
            fail("A required fixture tool is unavailable: " + tool)
    verify_fixtures(os.environ)
    prefix = ("baseline-" if baseline_mode() else "fault-" if fault_mode()
              else "smoke-" if smoke_mode() else "http-")
    artifacts = ROOT / ".logs/dp02" / (prefix + os.environ["UNIPO_DP02_MYSQL_RUN_ID"])
    runtime = artifacts / "runtime"
    runtime.mkdir(parents=True, exist_ok=False)
    snapshot = artifacts / "backend.jar"
    shutil.copy2(jar, snapshot)
    (artifacts / "backend.sha256").write_text(hashlib.sha256(snapshot.read_bytes()).hexdigest() + "\n", encoding="ascii")
    if baseline_mode():
        revision = os.environ.get("UNIPO_DP02_BASELINE_REVISION", "")
        if not 7 <= len(revision) <= 40 or any(c not in "0123456789abcdef" for c in revision):
            fail("The legacy fixture must record the archived source revision")
        (artifacts / "source-revision.txt").write_text(revision + "\n", encoding="ascii")
    env = application_environment(os.environ)
    env["UNIPO_DP02_ARTIFACT_DIR"] = str(artifacts)
    applications = {}
    logs = []
    closing = threading.Event()
    controller = None

    def stop_application(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start_instance(label):
        name, port = "instance-" + label, 28080 if label == "a" else 28081
        log = (artifacts / (name + ".log")).open("ab")
        logs.append(log)
        process = subprocess.Popen([
            "java", "-Xms64m", "-Xmx256m", "-jar", str(snapshot),
            "--server.address=127.0.0.1", "--server.port=" + str(port),
            "--spring.config.import=", "--spring.application.name=" + name,
        ], cwd=runtime, env=env, stdout=log, stderr=subprocess.STDOUT)
        applications[label] = process
        await_application(process, port, closing)

    def restart(label):
        stop_application(applications[label])
        if closing.is_set():
            fail("The fixture is shutting down")
        start_instance(label)

    try:
        # The relative ../.env import resolves only inside this newly created, private run directory.
        start_instance("a")
        start_instance("b")
        if fault_mode():
            controller = FixtureControl(artifacts, env, restart, applications, closing)
            env["UNIPO_DP02_CONTROL_SOCKET"] = str(controller.path)
        print("Two loopback application fixtures are ready. Results: " + str(artifacts), flush=True)
        with (artifacts / "results.log").open("wb") as result_log:
            client = subprocess.Popen([sys.executable, str(client_script())],
                                      env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            try:
                for line in iter(client.stdout.readline, b""):
                    sys.stdout.buffer.write(line)
                    sys.stdout.buffer.flush()
                    result_log.write(line)
                return client.wait()
            finally:
                if client.poll() is None:
                    client.terminate()
                    client.wait(timeout=5)
    finally:
        closing.set()
        if controller is not None:
            controller.close()
        # Popen owns these exact, unreaped children; existing system processes are never selected by a name.
        for process in reversed(list(applications.values())):
            stop_application(process)
        for log in logs:
            log.close()


def main():
    os.umask(0o077)
    smoke_mode()  # Validate mutually exclusive mode flags before creating any resources.
    jar = backend_jar()
    if not client_script().is_file():
        fail("The HTTP client script is not ready; no fixture was started")
    if "--inside-fixtures" not in sys.argv[1:]:
        env = os.environ.copy()
        if fault_mode():
            env["UNIPO_DP02_PERSISTENT_FIXTURE"] = "1"
        env.setdefault("UNIPO_DP02_REDIS_IMAGE", "docker.m.daocloud.io/library/redis:7-alpine")
        os.execve("/bin/bash", ["bash", str(FIXTURE_DIR / "mysql-identity.sh"),
                  "bash", str(FIXTURE_DIR / "redis-session.sh"),
                  sys.executable, str(Path(__file__).resolve()), "--inside-fixtures"], env)
    return run_http(jar)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (RuntimeError, subprocess.SubprocessError) as error:
        # Configuration and ownership errors contain no credentials; child process output stays in owned logs.
        print("HTTP fixture failed: " + str(error), file=sys.stderr)
        sys.exit(2)
