"""Read-only Beijing-day workbench report. No browser, model or delivery calls."""
import argparse
from contextlib import closing
from collections import Counter
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sqlite3

BEIJING = timezone(timedelta(hours=8))
PLATFORMS = {"boss": "BOSS直聘", "shixiseng": "实习僧", "zhilian": "智联招聘", "job51": "应届生实习"}
FAULT_TYPES = {"round_failed", "retry_failed", "round_expired", "manual_delivery_failed"}


def instant(value):
    if not value:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("记录时间缺少时区，不能可靠统计")
    return parsed


def reason_text(message):
    if "Timeout" in message or "timeout" in message.lower():
        timeout = re.search(r"Timeout\s+(\d+)ms", message)
        return "岗位页面加载或读取超时" + (f"（{int(timeout[1]) / 1000:g}秒）" if timeout else "")
    text = message.splitlines()[0].strip()
    if "Error {" in text:
        match = re.search(r"message=['\"]?([^\n]+)", message)
        text = match[1].strip(" '\"") if match else "浏览器操作失败，详细原因见本机故障记录"
    text = re.sub(r"[A-Za-z]:[\\/][^\s；，]+", "[本机路径]", text)
    return text[:240] or "故障原因未记录，请查看本机日志"


def build_report(database, day, now=None):
    now = now or datetime.now(BEIJING)
    start = datetime.combine(day, datetime.min.time(), BEIJING)
    end = start + timedelta(days=1)
    if day > now.astimezone(BEIJING).date():
        raise ValueError("不能生成未来日期的日报")
    uri = Path(database).resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True, timeout=10)) as db:
        db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN")
        rounds = {rid: (platform, json.loads(body)) for rid, platform, at, body in db.execute(
            "SELECT id,platform,started_at,body FROM rounds") if start <= instant(at) < end and platform in PLATFORMS}
        reads = {p: set() for p in PLATFORMS}
        read_occurrences = Counter()
        # Read only status metadata: never include resume, account or job descriptions in the report.
        existing = set()
        for platform, job, rid, already in db.execute(
            "SELECT platform,job_id,round_id,json_extract(body,'$.alreadySubmittedOnPlatform') FROM candidates"):
            if already and rid in rounds:
                existing.add((platform, job))
            if rid in rounds and platform in PLATFORMS:
                reads[platform].add(job)
                read_occurrences[platform] += 1
        confirmed = {p: set() for p in PLATFORMS}
        uncertain = {p: set() for p in PLATFORMS}
        for platform, job, state in db.execute("SELECT platform,job_id,state FROM claims WHERE day=?", (day.isoformat(),)):
            if platform not in PLATFORMS:
                continue
            if state == "confirmed" and (platform, job) not in existing:
                confirmed[platform].add(job)
            elif state in {"in_progress", "uncertain"}:
                uncertain[platform].add(job)
        failures = {}
        retry_success = set()
        retry_started = set()
        quotas = {p: [] for p in PLATFORMS}
        for platform, rid, kind, message, at in db.execute("SELECT platform,round_id,type,message,at FROM events ORDER BY at"):
            if platform not in PLATFORMS or not start <= instant(at) < end:
                continue
            key = (platform, rid)
            if kind in FAULT_TYPES:
                previous = failures.get(key)
                if previous is None or kind == "round_failed":
                    failures[key] = {"round": rid, "time": instant(at).astimezone(BEIJING).strftime("%H:%M:%S"), "reason": reason_text(message)}
            elif kind == "retry_started":
                retry_started.add(key)
            elif kind == "retry_succeeded":
                retry_success.add(key)
            elif kind == "daily_limit":
                quotas[platform].append({"time": instant(at).astimezone(BEIJING).strftime("%H:%M:%S"), "reason": reason_text(message)})
        result = []
        for platform, label in PLATFORMS.items():
            issues = []
            for (p, rid), failure in failures.items():
                if p != platform:
                    continue
                retry_key = (p, rid + "|retry-1")
                issue = dict(failure)
                issue["retryResult"] = "重试成功" if retry_key in retry_success else "重试失败" if retry_key in failures else "重试已安排，未见成功记录" if retry_key in retry_started else "未记录自动重试"
                if rid.endswith("|retry-1"):
                    issue["retryResult"] = "本次为重试，仍失败"
                issues.append(issue)
            current_rounds = [body for p, body in rounds.values() if p == platform]
            result.append({"platform": platform, "label": label, "searchedJobs": len(reads[platform]),
                "confirmedDeliveries": len(confirmed[platform]), "unconfirmedDeliveries": len(uncertain[platform] - confirmed[platform]),
                "readOccurrences": read_occurrences[platform], "preExcludedOccurrences": sum(r.get("preExcluded", 0) for r in current_rounds),
                "rounds": len(current_rounds), "runningRounds": sum(r.get("state") == "running" for r in current_rounds),
                "faultCount": len(issues), "faults": issues, "dailyLimits": quotas[platform]})
        return {"date": day.isoformat(), "generatedAt": now.astimezone(BEIJING).isoformat(),
            "partial": day == now.astimezone(BEIJING).date() and now.astimezone(BEIJING).hour < 17,
            "definitions": {"searchedJobs": "读取过详情的岗位，平台内当日去重；不含仅在列表预过滤的条目", "confirmedDeliveries": "当日已确认完成的新投递，平台内去重；排除网站此前已投递的岗位", "faultCount": "失败轮次或单条投递次数；重试再次失败另计一次，同轮重复日志只计一次；当日上限单独说明"},
            "platforms": result, "total": {key: sum(p[key] for p in result) for key in ["searchedJobs", "confirmedDeliveries", "unconfirmedDeliveries", "faultCount"]}}


def markdown(report):
    lines = [f"# 实习投递日报 · {report['date']}（北京时间）", "", "| 平台 | 检索岗位 | 确认投递 | 待核实投递 | 故障次数 |", "|---|---:|---:|---:|---:|"]
    for p in report["platforms"]:
        lines.append(f"| {p['label']} | {p['searchedJobs']} | {p['confirmedDeliveries']} | {p['unconfirmedDeliveries']} | {p['faultCount']} |")
    t = report["total"]
    lines += [f"| 合计 | {t['searchedJobs']} | {t['confirmedDeliveries']} | {t['unconfirmedDeliveries']} | {t['faultCount']} |", ""]
    for p in report["platforms"]:
        if p["faults"] or p["dailyLimits"]:
            lines += [f"**{p['label']}故障与上限**", ""]
            for fault in p["faults"]:
                lines.append(f"- {fault['time']}：{fault['reason']}；{fault['retryResult']}。")
            for limit in p["dailyLimits"]:
                lines.append(f"- {limit['time']} 达到网站当日上限：{limit['reason']}")
            lines.append("")
        if p["runningRounds"]:
            lines.append(f"- {p['label']}仍有{p['runningRounds']}轮标记为运行中，数据可能尚未完整。")
    lines += ["统计口径：检索为读取过详情的岗位，平台内当天去重；投递只计确认完成的新投递，未确认不计成功。预过滤条目未混入检索数。跨平台相同岗位分别统计。", "故障按失败轮次统计，重试仍失败另计一次，同一轮重复错误日志只计一次；当日上限单独列出。"]
    if report["partial"]:
        lines.append("当前为当天中途预览，非最终日报。")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    root = Path(__file__).resolve().parent.parent
    parser.add_argument("--date", type=date.fromisoformat, default=datetime.now(BEIJING).date())
    parser.add_argument("--database", type=Path, default=root / "get_jobs/data/parallel/workbench.db")
    parser.add_argument("--output", type=Path, default=root / "outputs/日报")
    args = parser.parse_args()
    report = build_report(args.database, args.date)
    args.output.mkdir(parents=True, exist_ok=True)
    for suffix, body in [("json", json.dumps(report, ensure_ascii=False, indent=2)), ("md", markdown(report))]:
        target = args.output / f"实习投递日报-{args.date}.{suffix}"
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_text(body, encoding="utf-8")
        temporary.replace(target)
    report["reportPath"] = str(args.output / f"实习投递日报-{args.date}.md")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
