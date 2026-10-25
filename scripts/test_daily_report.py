import hashlib
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from datetime import date, datetime

from daily_report import BEIJING, build_report, markdown


class DailyReportTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "test.db"
        with closing(sqlite3.connect(self.path)) as db, db:
            db.executescript("CREATE TABLE rounds(id,platform,started_at,body);CREATE TABLE candidates(platform,job_id,round_id,body);CREATE TABLE claims(platform,job_id,state,day);CREATE TABLE events(platform,round_id,type,message,at);")

    def insert(self, table, rows):
        with closing(sqlite3.connect(self.path)) as db, db:
            for row in rows:
                db.execute(f"INSERT INTO {table} VALUES({','.join('?' for _ in row)})", row)

    def report(self):
        return build_report(self.path, date(2026, 10, 23), datetime(2026, 10, 23, 17, 30, tzinfo=BEIJING))

    def test_beijing_boundary_and_repeated_jobs_are_counted_once(self):
        self.insert("rounds", [("old", "boss", "2026-10-22T15:59:59Z", "{}"), ("a", "boss", "2026-10-22T16:00:00Z", "{}"), ("b", "boss", "2026-10-23T00:00:00Z", "{}"), ("next", "boss", "2026-10-23T16:00:00Z", "{}")])
        self.insert("candidates", [("boss", "old", "old", "{}"), ("boss", "same", "a", "{}"), ("boss", "same", "b", "{}"), ("boss", "next", "next", "{}")])
        boss = self.report()["platforms"][0]
        self.assertEqual((1, 2), (boss["searchedJobs"], boss["readOccurrences"]))

    def test_delivery_uses_confirmation_not_apply_decision_or_existing_site_submission(self):
        self.insert("rounds", [("r", "boss", "2026-10-23T00:00:00Z", "{}"), ("later", "boss", "2026-10-24T00:00:00Z", "{}")])
        self.insert("claims", [("boss", "sent", "confirmed", "2026-10-23"), ("boss", "maybe", "uncertain", "2026-10-23"), ("boss", "old", "confirmed", "2026-10-22"), ("boss", "existing", "confirmed", "2026-10-23")])
        self.insert("candidates", [("boss", "existing", "r", json.dumps({"alreadySubmittedOnPlatform": True})), ("boss", "sent", "later", json.dumps({"alreadySubmittedOnPlatform": True}))])
        boss = self.report()["platforms"][0]
        self.assertEqual(1, boss["confirmedDeliveries"])
        self.assertEqual(1, boss["unconfirmedDeliveries"])

    def test_faults_deduplicate_logs_but_keep_failed_retry_and_limits_separate(self):
        self.insert("events", [("boss", "r", "round_failed", "模型网络中断", "2026-10-23T00:00:00Z"), ("boss", "r|retry-1", "retry_started", "重试", "2026-10-23T00:00:01Z"), ("boss", "r|retry-1", "round_failed", "网络中断", "2026-10-23T00:01:00Z"), ("boss", "r|retry-1", "retry_failed", "仍失败", "2026-10-23T00:01:01Z"), ("boss", "q", "daily_limit", "今日上限", "2026-10-23T00:02:00Z"), ("shixiseng", "s", "round_failed", "Timeout 8000ms exceeded", "2026-10-23T00:03:00Z"), ("shixiseng", "s|retry-1", "retry_succeeded", "完成", "2026-10-23T00:04:00Z")])
        report = self.report()
        self.assertEqual(2, report["platforms"][0]["faultCount"])
        self.assertEqual("重试失败", report["platforms"][0]["faults"][0]["retryResult"])
        self.assertEqual(1, len(report["platforms"][0]["dailyLimits"]))
        self.assertEqual("重试成功", report["platforms"][1]["faults"][0]["retryResult"])
        self.assertIn("8秒", markdown(report))

    def test_report_is_read_only_and_missing_database_is_not_zero(self):
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.report()
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).hexdigest())
        missing = Path(self.folder.name) / "missing.db"
        with self.assertRaises(sqlite3.OperationalError):
            build_report(missing, date(2026, 10, 23))
        self.assertFalse(missing.exists())


if __name__ == "__main__":
    unittest.main()
