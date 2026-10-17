#!/usr/bin/env python3
"""DB 컷오버 오케스트레이션 스크립트 (초안).

모든 백엔드 인스턴스에 같은 migration_id·epoch로 관리 API를 호출하고,
DMS 최종 반영·검증, ProxySQL 목적지 전환을 순서대로 수행한다.

    시작 전 점검 → AI 시작 지연 → AI 작업 0 대기 → drain → freeze(잔여 0)
    → [원본 쓰기 차단] → DMS 최종 반영·검증 → DMS 중지 → ProxySQL 전환·확인
    → resume(RDS) → OPEN 대기 → 결과 보고

원칙 (docs/db/cut-over.md)
- RDS 쓰기 허용(resume RDS) 전에 실패하면 ProxySQL을 원본으로 되돌리고 resume(ORIGIN)으로 취소한다.
- resume(RDS)를 보낸 뒤에는 취소하지 않는다. RDS를 유지하고 원인을 수정한다.
- 실패나 시간 초과만으로 쓰기 제한을 풀지 않는다. 취소도 정해진 절차(원복 → ORIGIN 재개)로만 한다.
- CDC 지연 0만으로 완료를 판단하지 않는다. DMS 테이블 검증 결과를 함께 본다.

의존성: Python 3.9+, 표준 라이브러리, `aws` CLI(DMS·SSM), `mysql` CLI(ProxySQL·원본 MySQL)

사용 예
    export MIGRATION_ADMIN_KEY=...            # 또는 설정의 admin_key_ssm_parameter
    export PROXYSQL_ADMIN_PASSWORD=...
    python3 cutover.py --config cutover.json --dry-run    # 실제 변경 없이 순서만 출력
    python3 cutover.py --config cutover.json              # 실제 실행
    python3 cutover.py --config cutover.json --status     # 인스턴스 상태만 조회

비밀 값(관리자 키, DB 비밀번호)은 환경변수나 SSM에서만 읽고, 출력·보고서·명령 인자에 남기지 않는다.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

ADMIN_KEY_HEADER = "X-Migration-Admin-Key"
CONTROL_PATH = "/internal/migration-control"
# mysql CLI 실행 명령. 설정의 mysql_command로 바꿀 수 있다
# (예: 로컬 mysql 9.x 클라이언트는 mysql_native_password를 지원하지 않으므로 컨테이너 안의 8.4 클라이언트를 쓴다)
MYSQL_COMMAND = ["mysql"]


class CutoverAbort(Exception):
    """취소 절차로 넘어가야 하는 실패"""


class CutoverFatal(Exception):
    """RDS 쓰기를 허용한 뒤의 실패. 취소하지 않고 사람이 대응한다"""


# ---------------------------------------------------------------- 출력·기록

class Recorder:
    """단계별 시각과 결과를 남기고, 끝나면 JSON 보고서로 저장한다"""

    def __init__(self, report_path):
        self.report_path = report_path
        self.started = time.monotonic()
        self.marks = {}
        self.events = []

    def log(self, message, level="INFO"):
        elapsed = time.monotonic() - self.started
        line = "[{:>7.2f}s] {:<5} {}".format(elapsed, level, message)
        print(line, flush=True)
        self.events.append({"at": now_iso(), "level": level, "message": message})

    def mark(self, name):
        self.marks[name] = time.monotonic()
        self.log("── {}".format(name))

    def between(self, start, end):
        if start in self.marks and end in self.marks:
            return round((self.marks[end] - self.marks[start]) * 1000)
        return None

    def save(self, extra):
        report = {
            "saved_at": now_iso(),
            "durations_ms": {
                "ai_wait": self.between("defer_ai_start", "ai_drained"),
                "drain": self.between("drain", "frozen"),
                "cdc_catchup": self.between("cdc_marker_written", "cdc_caught_up"),
                "dms_verify": self.between("dms_verify", "dms_verified"),
                "write_stop": self.between("frozen", "resume_sent"),
                "resume_to_open": self.between("resume_sent", "opened"),
            },
            "events": self.events,
        }
        report.update(extra)
        with open(self.report_path, "w", encoding="utf-8") as file:
            json.dump(report, file, ensure_ascii=False, indent=2)
        self.log("보고서 저장: {}".format(self.report_path))


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def confirm(question, assume_yes):
    if assume_yes:
        print("{} [자동 승인]".format(question))
        return True
    answer = input("{} [y/N] ".format(question)).strip().lower()
    return answer in ("y", "yes")


# ---------------------------------------------------------------- 외부 명령

def run(command, env_extra=None, input_text=None, check=True):
    """비밀 값은 env_extra로만 전달한다 (명령 인자로 넘기지 않는다)"""
    if shutil.which(command[0]) is None:
        hints = {
            "aws": "AWS CLI가 필요하다 (brew install awscli). SSM 키 조회·EC2 인스턴스 찾기·DMS 단계에서 쓴다. "
                   "로컬에서는 MIGRATION_ADMIN_KEY 환경변수, 설정의 instances 목록을 쓰고 dms 항목을 빼면 호출하지 않는다",
            "mysql": "mysql CLI가 필요하다 (brew install mysql-client). ProxySQL·원본 MySQL 단계에서 쓴다",
        }
        raise SystemExit("'{}' 명령을 찾을 수 없다. {}".format(command[0], hints.get(command[0], "")))
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    result = subprocess.run(command, input=input_text, capture_output=True, text=True, env=env)
    if check and result.returncode != 0:
        raise RuntimeError("{} 실패: {}".format(command[0], result.stderr.strip()[:500]))
    return result.stdout


def aws(args, region):
    output = run(["aws"] + args + ["--region", region, "--output", "json"])
    return json.loads(output) if output.strip() else {}


def mysql_query(conn, sql):
    """mysql CLI로 실행하고 탭 구분 행 목록을 돌려준다. 비밀번호는 MYSQL_PWD로 전달한다"""
    password = os.environ.get(conn["password_env"], "")
    output = run(
        MYSQL_COMMAND + ["-h", conn["host"], "-P", str(conn["port"]), "-u", conn["user"]]
        + list(conn.get("options", []))
        + ["--batch", "--skip-column-names", "--connect-timeout=5", "-e", sql],
        env_extra={"MYSQL_PWD": password},
    )
    return [line.split("\t") for line in output.splitlines() if line.strip()]


# ---------------------------------------------------------------- 관리 API

class Instance:
    def __init__(self, name, base_url):
        self.name = name
        self.base_url = base_url.rstrip("/")

    def __repr__(self):
        return self.name


class AdminApi:
    def __init__(self, admin_key, timeout_seconds):
        self.admin_key = admin_key
        self.timeout_seconds = timeout_seconds

    def call(self, instance, method, action, body=None, timeout=None):
        url = "{}{}/{}".format(instance.base_url, CONTROL_PATH, action)
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header(ADMIN_KEY_HEADER, self.admin_key)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout_seconds) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw or b"{}")
            except ValueError:
                return error.code, {"message": raw.decode(errors="replace")[:200]}

    def status(self, instance):
        code, body = self.call(instance, "GET", "status")
        if code != 200:
            raise RuntimeError("{} status 조회 실패: HTTP {} {}".format(instance, code, body.get("message")))
        return body["data"]


def parallel(instances, function):
    """인스턴스마다 동시에 호출하고 {instance: (결과, 예외)}를 돌려준다"""
    def wrapped(instance):
        try:
            return instance, function(instance), None
        except Exception as error:  # noqa: BLE001 - 인스턴스별 실패를 모아서 판단한다
            return instance, None, error

    with ThreadPoolExecutor(max_workers=max(1, len(instances))) as pool:
        return {instance: (result, error) for instance, result, error in pool.map(wrapped, instances)}


# ---------------------------------------------------------------- 오케스트레이터

class Cutover:
    def __init__(self, config, args, recorder):
        self.config = config
        self.args = args
        self.rec = recorder
        self.dry_run = args.dry_run
        self.migration_id = config["migration_id"]
        self.epoch = int(config["epoch"])
        self.timeouts = config.get("timeouts", {})
        self.api = AdminApi(load_admin_key(config), self.timeouts.get("http_seconds", 10))
        self.instances = discover_instances(config)
        # 취소 시 무엇을 되돌려야 하는지 추적한다
        self.proxysql_switched = False
        self.source_read_only_set = False
        self.dms_stopped = False
        self.rds_writes_allowed = False
        self.marker_id = None

    # ---- 전체 흐름

    def execute(self):
        self.preflight()
        try:
            self.defer_ai_start()
            self.wait_ai_jobs()
            self.drain()
            self.freeze()
            self.write_cdc_marker()
            self.block_source_writes()
            self.wait_cdc_marker()
            self.verify_dms()
            self.stop_dms()
            self.switch_proxysql()
            self.verify_proxysql_route()
        except CutoverAbort as reason:
            self.rec.log("컷오버 취소: {}".format(reason), "ERROR")
            self.abort()
            return False
        except KeyboardInterrupt:
            self.rec.log("사용자 중단", "ERROR")
            self.abort()
            return False
        except Exception as error:  # noqa: BLE001 - RDS 쓰기 허용 전의 예상 못 한 실패도 취소 절차로 원복한다
            self.rec.log("예상하지 못한 오류로 컷오버 취소: {!r}".format(error), "ERROR")
            self.abort()
            return False
        # 여기부터는 RDS 쓰기 허용. 실패해도 취소하지 않는다
        self.resume_rds()
        self.wait_open()
        self.retire_source()
        self.report()
        return True

    # ---- 단계

    def preflight(self):
        self.rec.mark("preflight")
        self.rec.log("migration_id={} epoch={} 인스턴스 {}개: {}".format(
            self.migration_id, self.epoch, len(self.instances), self.instances))
        problems = []
        for instance, (status, error) in parallel(self.instances, self.api.status).items():
            if error:
                problems.append("{}: {}".format(instance, error))
                continue
            self.rec.log("{} phase={} enabled={} epoch={} in_flight={} ai_deferred={}".format(
                instance, status["phase"], status["migration_enabled"], status["epoch"],
                status["in_flight"]["total"], status["deferred_ai_jobs"]["enabled"]))
            if not status["migration_enabled"]:
                problems.append("{}: migration_enabled=false".format(instance))
            if status["phase"] != "OPEN" or status["deferred_ai_jobs"]["enabled"]:
                problems.append("{}: 이미 컷오버가 진행 중이다 (phase={})".format(instance, status["phase"]))
        for check in (self.preflight_dms, self.preflight_proxysql, self.preflight_cdc_marker):
            try:
                check(problems)
            except (Exception, SystemExit) as error:  # noqa: BLE001 - 접속 실패도 점검 실패로 모은다
                problems.append("{} 실패: {}".format(check.__name__, error))
        if problems:
            for problem in problems:
                self.rec.log(problem, "ERROR")
            raise SystemExit("시작 전 점검 실패. 아무것도 변경하지 않았다")
        if not confirm("위 상태로 컷오버를 시작할까요?", self.args.yes):
            raise SystemExit("시작하지 않았다")

    def preflight_dms(self, problems):
        dms = self.config.get("dms")
        if not dms:
            self.rec.log("DMS 설정 없음: DMS 단계를 건너뛴다", "WARN")
            return
        task = describe_dms_task(dms)
        self.rec.log("DMS task status={} full_load={}%".format(
            task.get("Status"), task.get("ReplicationTaskStats", {}).get("FullLoadProgressPercent")))
        if task.get("Status") != "running":
            problems.append("DMS task가 running이 아니다: {}".format(task.get("Status")))

    def preflight_cdc_marker(self, problems):
        marker = self.config.get("cdc_marker")
        if not marker:
            self.rec.log("CDC 마커 설정 없음: 마지막 변경 반영 확인을 DMS 검증에만 의존한다", "WARN")
            return
        for side in ("source", "target"):
            try:
                mysql_query(marker[side], "SELECT COUNT(*) FROM {}".format(marker["table"]))
            except Exception as error:  # noqa: BLE001
                problems.append("CDC 마커 테이블 {}을 {}에서 읽을 수 없다: {}".format(marker["table"], side, error))
        self.rec.log("CDC 마커 테이블 확인: {}".format(marker["table"]))

    def preflight_proxysql(self, problems):
        proxysql = self.config.get("proxysql")
        if not proxysql:
            self.rec.log("ProxySQL 설정 없음: 경로 전환 단계를 건너뛴다", "WARN")
            return
        rows = mysql_query(proxysql["admin"],
                           "SELECT hostgroup_id, hostname, port, status FROM runtime_mysql_servers")
        self.rec.log("ProxySQL runtime 서버: {}".format(rows))
        source = proxysql["source"]
        targets = {(row[1], row[2]) for row in rows if int(row[0]) in proxysql["hostgroups"]}
        if targets != {(source["host"], str(source["port"]))}:
            problems.append("ProxySQL 목적지가 원본이 아니다: {}".format(targets))

    def defer_ai_start(self):
        self.rec.mark("defer_ai_start")
        self.command_all("defer-ai-start", expect_phase="OPEN")

    def wait_ai_jobs(self):
        timeout = self.timeouts.get("ai_wait_seconds", 360)
        while True:
            if self.poll_until(lambda s: s["in_flight"]["by_type"]["AI_JOB"] == 0, timeout, "AI_JOB 0"):
                self.rec.mark("ai_drained")
                return
            if not confirm("AI 작업이 아직 남았다. 더 기다릴까요? (N이면 컷오버 취소)", False):
                raise CutoverAbort("AI 작업 대기 시간 초과")

    def drain(self):
        self.rec.mark("drain")
        self.command_all("drain", expect_phase="DRAINING")

    def freeze(self):
        deadline = time.monotonic() + self.timeouts.get("freeze_total_seconds", 120)
        wait_seconds = self.timeouts.get("freeze_wait_seconds", 30)
        pending = list(self.instances)
        while pending:
            results = parallel(pending, lambda i: self.command(i, "freeze", {"wait_seconds": wait_seconds},
                                                              timeout=wait_seconds + 10))
            pending = []
            for instance, (result, error) in results.items():
                if error:
                    raise CutoverAbort("{} freeze 호출 실패: {}".format(instance, error))
                code, body = result
                if code == 200:
                    continue
                remaining = (body.get("data") or {}).get("remaining_work") or []
                if body.get("message") == "migration_remaining_work":
                    self.rec.log("{} 잔여 작업: {}".format(
                        instance, [item["description"] for item in remaining][:5]), "WARN")
                    pending.append(instance)
                else:
                    raise CutoverAbort("{} freeze 거부: HTTP {} {}".format(instance, code, body.get("message")))
            if pending and time.monotonic() > deadline:
                raise CutoverAbort("freeze 시간 초과. 잔여 작업이 남은 인스턴스: {}".format(pending))
        self.rec.mark("frozen")

    def write_cdc_marker(self):
        """freeze로 앱 쓰기가 멈춘 뒤 원본에 마커를 쓴다. DMS는 binlog 순서대로 반영하므로
        이 행이 RDS에 보이면 그 전의 모든 변경도 반영된 것이다. super_read_only를 걸기 전에 쓴다"""
        marker = self.config.get("cdc_marker")
        if not marker:
            return
        self.marker_id = "{}-{}-{}".format(self.migration_id, self.epoch, int(time.time() * 1000)).replace("'", "")
        sql = "INSERT INTO {} (id, created_at) VALUES ('{}', NOW(6))".format(marker["table"], self.marker_id)
        self.step("CDC 마커 기록 (원본): {}".format(self.marker_id), lambda: mysql_query(marker["source"], sql))
        self.rec.mark("cdc_marker_written")

    def wait_cdc_marker(self):
        marker = self.config.get("cdc_marker")
        if not marker:
            return
        if self.dry_run:
            self.rec.log("[dry-run] 대기: CDC 마커가 RDS에 도착")
            return
        sql = "SELECT COUNT(*) FROM {} WHERE id = '{}'".format(marker["table"], self.marker_id)
        deadline = time.monotonic() + marker.get("timeout_seconds", 30)
        while True:
            try:
                arrived = int(mysql_query(marker["target"], sql)[0][0]) > 0
            except Exception as error:  # noqa: BLE001 - 일시적 접속 오류는 재시도한다
                self.rec.log("RDS 마커 조회 실패, 재시도: {}".format(error), "WARN")
                arrived = False
            if arrived:
                self.rec.mark("cdc_caught_up")
                self.rec.log("CDC 마커 RDS 도착: 마지막 변경까지 반영됨 ({} ms)".format(
                    self.rec.between("cdc_marker_written", "cdc_caught_up")))
                return
            if time.monotonic() > deadline:
                raise CutoverAbort("CDC 마커가 {}초 안에 RDS에 도착하지 않았다".format(marker.get("timeout_seconds", 30)))
            time.sleep(0.5)

    def block_source_writes(self):
        source_mysql = self.config.get("source_mysql")
        if not source_mysql or not source_mysql.get("set_super_read_only"):
            self.rec.log("원본 super_read_only 설정 안 함 (앱 freeze만으로 차단)", "WARN")
            return
        self.step("원본 MySQL super_read_only=ON",
                  lambda: mysql_query(source_mysql, "SET GLOBAL super_read_only = ON"))
        self.source_read_only_set = True

    def verify_dms(self):
        dms = self.config.get("dms")
        if not dms:
            return
        self.rec.mark("dms_verify")
        if self.dry_run:
            # 실제 컷오버는 freeze 이후라 Pending이 줄어들지만, dry-run은 쓰기가 계속되므로 현재 상태만 보여준다
            problems = dms_validation_problems(dms)
            self.rec.log("[dry-run] DMS 검증 현재 상태: {}".format(
                "통과 조건 충족" if not problems else "미충족 {}건 (예: {})".format(len(problems), problems[:3])))
            return
        deadline = time.monotonic() + self.timeouts.get("dms_verify_seconds", 60)
        while True:
            problems = dms_validation_problems(dms)
            if not problems:
                if "Not enabled" in dms.get("accepted_validation_states", []):
                    self.rec.log("DMS 데이터 검증 없이 통과시켰다 (accepted_validation_states에 Not enabled). "
                                 "운영 컷오버에서는 검증을 켠다", "WARN")
                self.rec.log("DMS 테이블 검증 통과")
                self.rec.mark("dms_verified")
                return
            if time.monotonic() > deadline:
                for problem in problems[:20]:
                    self.rec.log(problem, "ERROR")
                raise CutoverAbort("DMS 검증 미완료 또는 실패 ({}건)".format(len(problems)))
            time.sleep(2)

    def stop_dms(self):
        dms = self.config.get("dms")
        if not dms:
            return
        self.step("DMS task 중지", lambda: aws(
            ["dms", "stop-replication-task", "--replication-task-arn", dms["task_arn"]], dms["region"]))
        self.dms_stopped = True
        if self.dry_run:
            return
        deadline = time.monotonic() + self.timeouts.get("dms_stop_seconds", 120)
        while describe_dms_task(dms).get("Status") != "stopped":
            if time.monotonic() > deadline:
                raise CutoverAbort("DMS task가 stopped가 되지 않았다")
            time.sleep(2)
        self.rec.mark("dms_stopped")

    def switch_proxysql(self):
        proxysql = self.config.get("proxysql")
        if not proxysql:
            return
        target = proxysql["target"]
        sql = proxysql_soft_switch_sql(proxysql["hostgroups"], proxysql["source"], target)
        self.step("ProxySQL 목적지 → {}:{} (원본은 OFFLINE_SOFT: 진행 중 연결은 끊지 않음)".format(
            target["host"], target["port"]), lambda: mysql_query(proxysql["admin"], sql))
        self.proxysql_switched = True
        self.rec.mark("proxysql_switched")

    def verify_proxysql_route(self):
        proxysql = self.config.get("proxysql")
        if not proxysql or self.dry_run:
            return
        target = proxysql["target"]
        target_key = (target["host"], str(target["port"]))
        rows = mysql_query(proxysql["admin"],
                           "SELECT hostgroup_id, hostname, port, status FROM runtime_mysql_servers")
        servers = {(row[1], row[2]): row[3] for row in rows if int(row[0]) in proxysql["hostgroups"]}
        if servers.get(target_key) != "ONLINE":
            raise CutoverAbort("ProxySQL runtime에 RDS가 ONLINE으로 없다: {}".format(servers))
        # 원본은 OFFLINE_SOFT로 남는다. 새 세션은 받지 않고, 이미 묶인 세션만 반납될 때까지 쓴다.
        # 그 세션은 앱이 resume 직전에 커넥션 풀을 교체하면서 정리된다
        unexpected = {key: status for key, status in servers.items()
                      if key != target_key and status not in ("OFFLINE_SOFT", "OFFLINE_HARD")}
        if unexpected:
            raise CutoverAbort("RDS 외 서버가 아직 새 트래픽을 받을 수 있다: {}".format(unexpected))
        # 앱 계정으로 ProxySQL을 거친 실제 목적지 확인
        route = proxysql.get("route_check")
        if route:
            actual = mysql_query(route, route["query"])
            expected = route["expected"]
            if not actual or actual[0][0] != expected:
                raise CutoverAbort("ProxySQL 경유 목적지 확인 실패: {} (기대값 {})".format(actual, expected))
        self.rec.log("ProxySQL 경로 확인 완료")

    def retire_source(self):
        """OPEN 이후 원본에 묶인 연결이 모두 반납되면 ProxySQL에서 원본 항목을 지운다.
        앱이 resume 직전에 커넥션 풀을 교체하므로 보통 바로 0이 된다"""
        proxysql = self.config.get("proxysql")
        if not proxysql or not self.proxysql_switched:
            return
        source = proxysql["source"]
        if self.dry_run:
            self.rec.log("[dry-run] 원본 연결 0 확인 후 ProxySQL에서 원본 항목 삭제")
            return
        sql = ("SELECT COALESCE(SUM(ConnUsed),0) FROM stats_mysql_connection_pool "
               "WHERE srv_host='{}' AND srv_port={}".format(source["host"], int(source["port"])))
        deadline = time.monotonic() + self.timeouts.get("source_drain_seconds", 30)
        while True:
            used = int(mysql_query(proxysql["admin"], sql)[0][0])
            if used == 0:
                break
            if time.monotonic() > deadline:
                self.rec.log("원본에 묶인 연결 {}개가 남아 원본 항목을 남겨 둔다(OFFLINE_SOFT). "
                             "앱 커넥션 풀 교체 로그(migration_connection_pool_refreshed)를 확인하고, "
                             "0이 되면 수동으로 지운다: {}".format(
                                 used, proxysql_retire_source_sql(proxysql["hostgroups"], source)), "WARN")
                return
            time.sleep(0.5)
        try:
            mysql_query(proxysql["admin"], proxysql_retire_source_sql(proxysql["hostgroups"], source))
            self.rec.mark("source_retired")
            self.rec.log("ProxySQL에서 원본 항목 삭제 완료")
        except Exception as error:  # noqa: BLE001 - 정리 실패는 컷오버 결과에 영향이 없다
            self.rec.log("원본 항목 삭제 실패, 수동 정리 필요: {}".format(error), "WARN")

    def resume_rds(self):
        self.rec.mark("resume_sent")
        self.rds_writes_allowed = True
        results = parallel(self.instances, lambda i: self.command(i, "resume", {"target": "RDS"}))
        for instance, (result, error) in results.items():
            if error or result[0] not in (200, 202):
                # 취소하지 않는다. 같은 명령은 멱등이므로 재시도하거나 사람이 대응한다
                self.rec.log("{} resume 실패: {} {}. 수동 재시도 필요 (같은 명령 재전송은 안전)".format(
                    instance, error, result and result[1].get("message")), "ERROR")

    def wait_open(self):
        timeout = self.timeouts.get("open_wait_seconds", 120)
        if self.poll_until(lambda s: s["phase"] == "OPEN", timeout, "phase OPEN"):
            self.rec.mark("opened")
        else:
            self.rec.log("OPEN 도달 시간 초과. status의 pending_queue·resume 지표를 확인한다", "ERROR")

    def abort(self):
        """RDS 쓰기를 허용하기 전에만 호출된다. 원본 경로로 되돌린 뒤 ORIGIN으로 재개한다"""
        if self.rds_writes_allowed:
            raise CutoverFatal("RDS 쓰기 허용 후에는 취소하지 않는다")
        self.rec.mark("abort")
        proxysql = self.config.get("proxysql")
        if self.proxysql_switched and proxysql:
            sql = proxysql_revert_sql(proxysql["hostgroups"], proxysql["source"], proxysql["target"])
            self.step("ProxySQL 목적지 원복 → 원본 (RDS 항목 삭제, 원본 ONLINE)",
                      lambda: mysql_query(proxysql["admin"], sql))
        if self.source_read_only_set:
            self.step("원본 MySQL super_read_only=OFF",
                      lambda: mysql_query(self.config["source_mysql"], "SET GLOBAL super_read_only = OFF"))
        if self.dms_stopped:
            self.rec.log("DMS가 중지됐다. 원본 쓰기 재개 후 RDS에 반영하려면 DMS task를 다시 시작해야 한다", "WARN")
        # 단계별로 받을 수 있는 인스턴스만 취소된다. OPEN(아직 drain 전)은 지연만 해제한다
        for instance, (status, error) in parallel(self.instances, self.api.status).items():
            if error:
                self.rec.log("{} 상태 조회 실패, 수동 확인 필요: {}".format(instance, error), "ERROR")
                continue
            if status["phase"] == "OPEN":
                if status["deferred_ai_jobs"]["enabled"]:
                    self.command(instance, "cancel-ai-start-deferral")
            else:
                self.command(instance, "resume", {"target": "ORIGIN"})
        self.wait_open()
        self.report()

    def report(self):
        statuses = {}
        for instance, (status, error) in parallel(self.instances, self.api.status).items():
            if error:
                statuses[instance.name] = {"error": str(error)}
                continue
            statuses[instance.name] = {
                "phase": status["phase"],
                "resume_target": status["resume_target"],
                "timeline": status["timeline"],
                "pending_queue": status["pending_queue"]["counters"],
                "resume": status["resume"],
                "frozen_write_rejections": status["in_flight"].get("frozen_write_rejections"),
                "errors": status["errors"][:20],
            }
            self.rec.log("{} phase={} write_stop_ms={} resume={} timed_out={} frozen_rejections={} errors={}".format(
                instance, status["phase"], status["timeline"]["write_stop_duration_ms"],
                {key: status["resume"][key] for key in ("dispatched", "succeeded", "failed")},
                status["pending_queue"]["counters"]["timed_out"],
                status["in_flight"].get("frozen_write_rejections"), len(status["errors"])))
        self.rec.log("클러스터 쓰기 중단(freeze 완료 → resume 전송): {} ms".format(
            self.rec.between("frozen", "resume_sent")))
        self.rec.save({"migration_id": self.migration_id, "epoch": self.epoch,
                       "dry_run": self.dry_run, "instances": statuses})

    # ---- 공통

    def command(self, instance, action, extra=None, timeout=None):
        body = {"migration_id": self.migration_id, "epoch": self.epoch}
        body.update(extra or {})
        if self.dry_run:
            self.rec.log("[dry-run] {} POST {} {}".format(instance, action, extra or ""))
            return 200, {"message": "dry_run", "data": {}}
        code, response = self.api.call(instance, "POST", action, body, timeout)
        self.rec.log("{} {} → HTTP {} {}".format(instance, action, code, response.get("message")))
        return code, response

    def command_all(self, action, expect_phase):
        """모든 인스턴스가 성공해야 다음 단계로 간다. 하나라도 실패하면 취소한다"""
        for instance, (result, error) in parallel(self.instances, lambda i: self.command(i, action)).items():
            if error:
                raise CutoverAbort("{} {} 호출 실패: {}".format(instance, action, error))
            code, body = result
            if code not in (200, 202):
                raise CutoverAbort("{} {} 거부: HTTP {} {}".format(instance, action, code, body.get("message")))
            phase = ((body.get("data") or {}).get("status") or {}).get("phase")
            if not self.dry_run and phase != expect_phase:
                raise CutoverAbort("{} {} 이후 phase가 {}가 아니다: {}".format(instance, action, expect_phase, phase))

    def poll_until(self, condition, timeout_seconds, label):
        if self.dry_run:
            self.rec.log("[dry-run] 대기: {}".format(label))
            return True
        deadline = time.monotonic() + timeout_seconds
        last_log = 0
        while time.monotonic() < deadline:
            results = parallel(self.instances, self.api.status)
            waiting = [instance for instance, (status, error) in results.items()
                       if error or not condition(status)]
            if not waiting:
                return True
            if time.monotonic() - last_log > 10:
                self.rec.log("대기 중({}): {}".format(label, waiting))
                last_log = time.monotonic()
            time.sleep(1)
        return False

    def step(self, label, action):
        if self.dry_run:
            self.rec.log("[dry-run] {}".format(label))
            return None
        self.rec.log(label)
        try:
            return action()
        except Exception as error:  # noqa: BLE001
            raise CutoverAbort("{} 실패: {}".format(label, error))


# ---------------------------------------------------------------- 설정·외부 시스템

def load_admin_key(config):
    key = os.environ.get("MIGRATION_ADMIN_KEY")
    parameter = config.get("admin_key_ssm_parameter")
    if not key and parameter:
        key = aws(["ssm", "get-parameter", "--name", parameter, "--with-decryption"],
                  config["region"])["Parameter"]["Value"]
    if not key:
        raise SystemExit("관리자 키가 없다: MIGRATION_ADMIN_KEY 또는 admin_key_ssm_parameter를 설정한다")
    return key


def discover_instances(config):
    """고정 목록(instances) 또는 EC2 태그로 찾은 실행 중 인스턴스의 사설 IP"""
    if config.get("instances"):
        return [Instance(item["name"], item["url"]) for item in config["instances"]]
    discovery = config["discovery"]
    reservations = aws(["ec2", "describe-instances", "--filters",
                        "Name=tag:{},Values={}".format(discovery["tag_key"], discovery["tag_value"]),
                        "Name=instance-state-name,Values=running"], config["region"])["Reservations"]
    instances = [
        Instance(item["InstanceId"], "http://{}:{}".format(item["PrivateIpAddress"], discovery.get("port", 8080)))
        for reservation in reservations for item in reservation["Instances"]
    ]
    if not instances:
        raise SystemExit("대상 인스턴스를 찾지 못했다")
    return instances


def describe_dms_task(dms):
    tasks = aws(["dms", "describe-replication-tasks", "--filters",
                 "Name=replication-task-arn,Values={}".format(dms["task_arn"])], dms["region"])
    return tasks["ReplicationTasks"][0]


def dms_validation_problems(dms):
    """검증이 끝나지 않았거나 실패한 테이블 목록. 비어 있으면 통과"""
    stats = aws(["dms", "describe-table-statistics", "--replication-task-arn", dms["task_arn"]],
                dms["region"])["TableStatistics"]
    allowed = set(dms.get("accepted_validation_states", ["Validated"]))
    problems = []
    for table in stats:
        name = "{}.{}".format(table["SchemaName"], table["TableName"])
        # 검증이 꺼진 태스크는 ValidationState가 비어 있다. accepted_validation_states에 "Not enabled"를 넣어야 통과한다
        state = table.get("ValidationState") or "Not enabled"
        if state not in allowed:
            problems.append("{} ValidationState={}".format(name, state))
        for field in ("ValidationFailedRecords", "ValidationPendingRecords", "ValidationSuspendedRecords"):
            if table.get(field, 0):
                problems.append("{} {}={}".format(name, field, table[field]))
        if table.get("TableState") not in ("Table completed", "Table loaded"):
            # CDC 진행 중 테이블은 'Table completed' 상태가 정상이다
            problems.append("{} TableState={}".format(name, table.get("TableState")))
    return problems


SERVER_COLUMNS = "weight, compression, max_connections, max_replication_lag, use_ssl, max_latency_ms"


def proxysql_soft_switch_sql(hostgroups, source, target):
    """RDS 항목을 원본 설정(use_ssl 등)을 복사해 추가하고 원본은 OFFLINE_SOFT로 바꾼다.
    OFFLINE_SOFT는 새 세션을 받지 않지만 이미 묶인 세션은 끊지 않으므로 진행 중인 읽기가 실패하지 않는다"""
    ids = ",".join(str(int(group)) for group in hostgroups)
    src_host, src_port = sql_text(source["host"]), int(source["port"])
    tgt_host, tgt_port = sql_text(target["host"]), int(target["port"])
    return (
        "INSERT OR REPLACE INTO mysql_servers (hostgroup_id, hostname, port, status, {cols}) "
        "SELECT hostgroup_id, '{th}', {tp}, 'ONLINE', {cols} FROM mysql_servers "
        "WHERE hostgroup_id IN ({ids}) AND hostname='{sh}' AND port={sp}; "
        "UPDATE mysql_servers SET status='OFFLINE_SOFT' WHERE hostgroup_id IN ({ids}) AND hostname='{sh}' AND port={sp}; "
        "LOAD MYSQL SERVERS TO RUNTIME; SAVE MYSQL SERVERS TO DISK;"
    ).format(cols=SERVER_COLUMNS, th=tgt_host, tp=tgt_port, sh=src_host, sp=src_port, ids=ids)


def proxysql_revert_sql(hostgroups, source, target):
    ids = ",".join(str(int(group)) for group in hostgroups)
    return (
        "DELETE FROM mysql_servers WHERE hostgroup_id IN ({ids}) AND hostname='{th}' AND port={tp}; "
        "UPDATE mysql_servers SET status='ONLINE' WHERE hostgroup_id IN ({ids}) AND hostname='{sh}' AND port={sp}; "
        "LOAD MYSQL SERVERS TO RUNTIME; SAVE MYSQL SERVERS TO DISK;"
    ).format(ids=ids, th=sql_text(target["host"]), tp=int(target["port"]),
             sh=sql_text(source["host"]), sp=int(source["port"]))


def proxysql_retire_source_sql(hostgroups, source):
    ids = ",".join(str(int(group)) for group in hostgroups)
    return (
        "DELETE FROM mysql_servers WHERE hostgroup_id IN ({ids}) AND hostname='{sh}' AND port={sp}; "
        "LOAD MYSQL SERVERS TO RUNTIME; SAVE MYSQL SERVERS TO DISK;"
    ).format(ids=ids, sh=sql_text(source["host"]), sp=int(source["port"]))


def sql_text(value):
    return str(value).replace("'", "''")


# ---------------------------------------------------------------- 진입점

def main():
    parser = argparse.ArgumentParser(description="DB 컷오버 오케스트레이션")
    parser.add_argument("--config", required=True, help="설정 JSON 경로 (cutover.example.json 참고)")
    parser.add_argument("--dry-run", action="store_true", help="변경 없이 실행 순서만 출력 (상태 조회는 실제로 한다)")
    parser.add_argument("--status", action="store_true", help="인스턴스 상태만 조회하고 끝낸다")
    parser.add_argument("--yes", action="store_true", help="시작 확인을 자동 승인 (리허설 자동화용)")
    parser.add_argument("--report", default=None, help="보고서 경로 (기본: cutover-<migration_id>-<epoch>.json)")
    args = parser.parse_args()

    with open(args.config, encoding="utf-8") as file:
        try:
            config = json.load(file)
        except json.JSONDecodeError as error:
            raise SystemExit("설정 파일 JSON 문법 오류: {} {}번째 줄 {}번째 칸 근처. "
                             "마지막 항목 뒤 쉼표, 주석(//, #), 따옴표 누락을 확인한다 ({})".format(
                                 args.config, error.lineno, error.colno, error.msg))
    if config.get("mysql_command"):
        MYSQL_COMMAND[:] = config["mysql_command"]
    report_path = args.report or "cutover-{}-{}.json".format(config["migration_id"], config["epoch"])
    recorder = Recorder(report_path)
    cutover = Cutover(config, args, recorder)

    if args.status:
        for instance, (status, error) in parallel(cutover.instances, cutover.api.status).items():
            print(instance, error or json.dumps(status, ensure_ascii=False, indent=2))
        return 0

    try:
        return 0 if cutover.execute() else 1
    except CutoverFatal as error:
        recorder.log(str(error), "ERROR")
        return 2


if __name__ == "__main__":
    sys.exit(main())
