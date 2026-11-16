#!/usr/bin/env python3
"""Local-only matching fixtures. No credentials, AI calls or ranking rules here."""
import argparse
import collections
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = Path(__file__).with_name("fixtures.json")


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True, **kwargs)


def sql(statement):
    result = compose("exec", "-T", "db", "sh", "-c",
                     'exec psql -X -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
                     input=statement, text=True, capture_output=True)
    return result.stdout.strip()


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def load_fixture():
    reports = json.loads(FIXTURE.read_text())["reports"]
    assert len({r["id"] for r in reports}) == len(reports), "Duplicate IDs"
    assert len({r["key"] for r in reports}) == len(reports), "Duplicate keys"
    for row in reports:
        uuid.UUID(row["id"])
        assert row["type"] in {"LOST", "FOUND"}
        assert row["status"] in {"ACTIVE", "RESOLVED"}
        assert row["owner"] in {"demo", "test"}
        assert 3 <= len(row["title"].strip()) <= 150
        assert 10 <= len(row["description"].strip()) <= 5000
        assert dt.date.fromisoformat(row["event_date"]) <= dt.date.today()
        assert -180 <= row["longitude"] <= 180 and -90 <= row["latitude"] <= 90
        assert row.get("split", "tuning") in {"tuning", "validation"}
    # A physical item and its deliberately similar decoys stay in the same split.
    for field in ("item", "family"):
        assignments = collections.defaultdict(set)
        for row in reports:
            assignments[row.get(field, row["item"])].add(row.get("split", "tuning"))
        assert all(len(splits) == 1 for splits in assignments.values()), f"Split leakage: {field}"
    return reports


def expected(lost, found):
    if lost["status"] != "ACTIVE" or found["status"] != "ACTIVE":
        return "EXCLUDED", "inactive_report"
    if lost["owner"] == found["owner"]:
        return "EXCLUDED", "same_author"
    if lost["item"] == found["item"]:
        return "MATCH", found["scenario"]
    if lost["item"] in found["ambiguous_for"]:
        return "UNCERTAIN", "insufficient_evidence"
    return "NO_MATCH", "different_synthetic_objects"


def local_preflight(reports):
    # Only a local Unix socket is allowed, including when DOCKER_HOST overrides context.
    host = os.environ.get("DOCKER_HOST")
    if not host:
        context = subprocess.run(["docker", "context", "inspect"], check=True,
                                 text=True, capture_output=True)
        host = json.loads(context.stdout)[0]["Endpoints"]["docker"]["Host"]
    if not host.startswith("unix://"):
        raise RuntimeError("Refusing non-local Docker endpoint")
    if sql("SELECT current_database();") != "foundly":
        raise RuntimeError("Expected local database named foundly")
    required = collections.Counter(r["owner"] for r in reports)
    accounts = json.loads(sql("SELECT coalesce(json_agg(t), '[]') FROM "
        "(SELECT display_name, count(*) AS n FROM app_user "
        "WHERE auth_issuer='http://localhost:8081/realms/foundly' "
        "GROUP BY display_name) t;"))
    counts = {r["display_name"]: r["n"] for r in accounts}
    if any(counts.get(owner) != 1 for owner in required):
        raise RuntimeError("Exactly one existing local profile for demo and test is required")
    categories = set(json.loads(sql("SELECT json_agg(code) FROM category WHERE active;")))
    if not {r["category"] for r in reports} <= categories:
        raise RuntimeError("Missing active categories")


def seed(reports, append=False):
    local_preflight(reports)
    running = compose("ps", "--status", "running", "--services",
                      text=True, capture_output=True).stdout.splitlines()
    if "backend" not in running:
        raise RuntimeError("Start the Compose backend first; stop any separate IDE backend")
    backup_dir = Path(tempfile.mkdtemp(prefix="foundly-before-matching-"))
    backup = backup_dir / "foundly.dump"
    os.chmod(backup_dir, 0o700)
    print(f"Backup directory: {backup_dir}", flush=True)
    compose("stop", "backend")
    try:
        with backup.open("wb") as stream:
            os.chmod(backup, 0o600)
            compose("exec", "-T", "db", "sh", "-c",
                    'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"', stdout=stream)
        if not backup.stat().st_size:
            raise RuntimeError("Empty backup; database unchanged")
        # Verify archive before deleting anything. pg_restore --list is read-only.
        compose("exec", "-T", "db", "pg_restore", "--list",
                input=backup.read_bytes(), stdout=subprocess.DEVNULL)
        data = literal(json.dumps(reports, ensure_ascii=False))
        # Existing fixture IDs are never overwritten in append mode. A content
        # mismatch aborts the whole transaction instead of changing manual edits.
        collision_check = """
DO $$ BEGIN
 IF EXISTS (
  SELECT 1 FROM seed_report s JOIN report r ON r.id=(s.r->>'id')::uuid
  JOIN app_user u ON u.id=r.author_id JOIN category c ON c.id=r.category_id
  WHERE r.title <> s.r->>'title' OR r.description <> s.r->>'description'
   OR r.type <> s.r->>'type' OR r.status <> s.r->>'status'
   OR r.color IS DISTINCT FROM s.r->>'color' OR c.code <> s.r->>'category'
   OR r.event_date <> (s.r->>'event_date')::date
   OR r.location_label <> s.r->>'location_label'
   OR ST_Distance(r.location_point, ST_SetSRID(ST_MakePoint(
       (s.r->>'longitude')::float8, (s.r->>'latitude')::float8),4326)::geography) > 0.01
   OR u.display_name <> s.r->>'owner' OR u.auth_issuer <> 'http://localhost:8081/realms/foundly'
 ) THEN RAISE EXCEPTION 'Existing fixture report differs; inspect manual changes before appending'; END IF;
END $$;
""" if append else "DELETE FROM report;"
        result = sql(f"""
BEGIN;
SET LOCAL standard_conforming_strings = on;
LOCK TABLE app_user, category IN SHARE MODE;
CREATE TEMP TABLE seed_report AS
SELECT value AS r, ordinality AS position FROM jsonb_array_elements({data}::jsonb) WITH ORDINALITY;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM seed_report s WHERE
   (SELECT count(*) FROM app_user u WHERE u.display_name=s.r->>'owner'
    AND u.auth_issuer='http://localhost:8081/realms/foundly') <> 1
   OR NOT EXISTS (SELECT 1 FROM category c WHERE c.code=s.r->>'category' AND c.active))
 THEN RAISE EXCEPTION 'Missing or ambiguous fixture accounts/categories'; END IF;
END $$;
{collision_check}
CREATE TEMP TABLE inserted_report(id uuid PRIMARY KEY);
WITH inserted AS (
INSERT INTO report(id, author_id, category_id, title, type, status, description, color,
 location_label, location_point, event_date, created_at, updated_at, resolved_at, version)
SELECT (s.r->>'id')::uuid, u.id, c.id, s.r->>'title', s.r->>'type', s.r->>'status',
 s.r->>'description', s.r->>'color', s.r->>'location_label',
 ST_SetSRID(ST_MakePoint((s.r->>'longitude')::float8, (s.r->>'latitude')::float8),4326)::geography,
 (s.r->>'event_date')::date, CURRENT_TIMESTAMP + s.position * interval '1 microsecond',
 CURRENT_TIMESTAMP, CASE WHEN s.r->>'status'='RESOLVED' THEN CURRENT_TIMESTAMP END, 0
FROM seed_report s JOIN app_user u ON u.display_name=s.r->>'owner'
 AND u.auth_issuer='http://localhost:8081/realms/foundly'
JOIN category c ON c.code=s.r->>'category'
WHERE NOT EXISTS (SELECT 1 FROM report existing WHERE existing.id=(s.r->>'id')::uuid)
RETURNING id)
INSERT INTO inserted_report SELECT id FROM inserted;
INSERT INTO matching_job(id, report_id, report_version, pipeline_version, available_at, created_at, updated_at)
SELECT gen_random_uuid(), id, version, 'v2', CURRENT_TIMESTAMP, created_at, CURRENT_TIMESTAMP
FROM report WHERE status='ACTIVE' AND id IN (SELECT id FROM inserted_report);
SELECT json_build_object('inserted_reports', (SELECT count(*) FROM inserted_report));
COMMIT;
SELECT json_build_object('reports', (SELECT count(*) FROM report),
 'pending_jobs', (SELECT count(*) FROM matching_job WHERE status='PENDING'));
""")
        print(result, flush=True)
    finally:
        compose("start", "backend")
    print(f"Backup retained: {backup}", flush=True)


def evaluate(reports, output_dir, split="tuning"):
    local_preflight(reports)
    jobs = json.loads(sql("SELECT coalesce(json_agg(t), '[]') FROM "
        "(SELECT status, count(*) AS n FROM matching_job GROUP BY status) t;"))
    print("Jobs:", jobs)
    actual = set(json.loads(sql("SELECT coalesce(json_agg(id::text), '[]') FROM report;")))
    if actual != {r["id"] for r in reports}:
        raise RuntimeError("Database report IDs differ from fixture; evaluation aborted")
    expected_jobs = sum(r["status"] == "ACTIVE" for r in reports)
    if jobs != [{"status": "COMPLETED", "n": expected_jobs}]:
        raise RuntimeError("Wait for all active fixture jobs to complete before evaluation")
    selected_reports = [r for r in reports if r.get("split", "tuning") == split]
    ids = ",".join(literal(r["id"]) + "::uuid" for r in selected_reports)
    rows = json.loads(sql(f"""
SELECT coalesce(json_agg(t), '[]') FROM (
 SELECT l.id AS lost_id, f.id AS found_id,
  CASE WHEN le.report_id IS NOT NULL AND fe.report_id IS NOT NULL
    THEN 1 - (le.embedding <=> fe.embedding) END AS raw_cosine,
  ST_Distance(l.location_point, f.location_point) AS distance_meters,
  f.event_date-l.event_date AS days_after_loss,
  m.status, m.semantic_score, m.final_score, m.location_score, m.date_score,
  m.category_score, m.evidence_coverage
 FROM report l CROSS JOIN report f
 LEFT JOIN report_embedding le ON le.report_id=l.id
 LEFT JOIN report_embedding fe ON fe.report_id=f.id
 LEFT JOIN report_match m ON m.lost_report_id=l.id AND m.found_report_id=f.id
 WHERE l.type='LOST' AND f.type='FOUND'
   AND l.id IN ({ids}) AND f.id IN ({ids})
) t;
"""))
    by_id = {r["id"]: r for r in reports}
    for row in rows:
        lost, found = by_id[row["lost_id"]], by_id[row["found_id"]]
        row["lost_key"], row["found_key"] = lost["key"], found["key"]
        row["expected"], row["reason"] = expected(lost, found)
    rows.sort(key=lambda r: (r["lost_key"], r["found_key"]))
    metadata = json.loads(sql("""
SELECT json_build_object(
 'embeddings', (SELECT json_agg(t) FROM
  (SELECT DISTINCT model_id, model_revision, preprocessing_version FROM report_embedding) t),
 'ranking_versions', (SELECT json_agg(DISTINCT ranking_version) FROM report_match));
"""))
    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / "pairs.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    for row in rows:
        if row["expected"] == "EXCLUDED" and row["status"] is not None:
            raise RuntimeError("An excluded pair was stored: " + row["found_key"])
    lines = ["# Ocena danych testowych", "", f"Wygenerowano: {dt.datetime.now(dt.timezone.utc).isoformat()}",
             "", f"Podzbiór: **{split}**. Obie strony ocenianej pary należą do tego podzbioru.",
             "", f"SHA-256 fixtures.json: `{hashlib.sha256(FIXTURE.read_bytes()).hexdigest()}`",
             "", "Metadane zapisanych wyników:", "", "```json", json.dumps(metadata, indent=2), "```",
             "", "Wyniki dotyczą syntetycznych scenariuszy, nie jakości na rzeczywistych ogłoszeniach.",
             "UNCERTAIN i EXCLUDED nie są liczone jako negatywne przykłady.",
             "Próg poniżej jest tylko symulacją na zapisanych parach, bez zmiany konfiguracji aplikacji.",
             "Brak zapisanej pary nie daje jej wyniku końcowego; surowy cosine jest w pairs.csv.", "",
             "| Próg finalScore | Poprawne sugestie | Błędne sugestie | Precision | Recall poprawnych par |",
             "|---|---:|---:|---:|---:|"]
    positives = sum(r["expected"] == "MATCH" for r in rows)
    for threshold in [0, 0.7, 0.75, 0.8, 0.85, 0.9]:
        selected = [r for r in rows if r["status"] in {"PROPOSED", "CONFIRMED"}
                    and r["final_score"] >= threshold and r["expected"] in {"MATCH", "NO_MATCH"}]
        tp = sum(r["expected"] == "MATCH" for r in selected)
        fp = len(selected) - tp
        precision = f"{tp / len(selected):.1%}" if selected else "—"
        lines.append(f"| {threshold:.2f} | {tp} | {fp} | {precision} | {tp / positives:.1%} |")
    lines += ["", "## Ranking dla LOST", "",
              "| Ogłoszenie | Top 1 | Pierwszy poprawny wynik | Poprawne w top 3 |",
              "|---|---|---:|---:|"]
    for lost in [r for r in selected_reports if r["type"] == "LOST"]:
        ranked = sorted((r for r in rows if r["lost_id"] == lost["id"] and r["status"] in {"PROPOSED", "CONFIRMED"}),
                        key=lambda r: (-r["final_score"], r["found_key"]))
        ranks = [i for i, r in enumerate(ranked, 1) if r["expected"] == "MATCH"]
        top = f"{ranked[0]['found_key']} ({ranked[0]['expected']})" if ranked else "—"
        lines.append(f"| {lost['key']} | {top} | {min(ranks) if ranks else '—'} | "
                     f"{sum(r['expected'] == 'MATCH' for r in ranked[:3])} |")
    lines += ["", "Pełne wyniki, w tym trudne negatywy, odległości i surowe cosine: `pairs.csv`.",
              "Remisy w tym raporcie rozstrzyga klucz scenariusza; API używa UUID pary.",
              "Ranking dotyczy zapisanych par z tego podzbioru. API korzysta z całej bazy.",
              "Zestaw tuning służy dostrojeniu. Validation otwórz po ustaleniu konfiguracji.", ""]
    report = "\n".join(lines)
    (output_dir / "summary.md").write_text(report)
    print(report)
    print(f"Results: {output_dir}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "seed", "append", "evaluate"])
    parser.add_argument("--apply", action="store_true", help="Authorize writing fixture reports")
    parser.add_argument("--split", choices=["tuning", "validation"], default="tuning")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    reports = load_fixture()
    labels = collections.Counter(expected(l, f)[0] for l in reports if l["type"] == "LOST"
                                 for f in reports if f["type"] == "FOUND")
    print(f"Fixture: {len(reports)} reports; expected pairs: {dict(labels)}", flush=True)
    print("Splits:", dict(collections.Counter(r.get("split", "tuning") for r in reports)))
    if args.command in {"seed", "append"}:
        if not args.apply:
            parser.error("Writes require --apply; seed replaces ALL reports, append only adds missing IDs")
        seed(reports, append=args.command == "append")
    elif args.command == "evaluate":
        output = args.output or Path("/tmp/foundly-matching-evaluation-v2") / args.split
        evaluate(reports, output.resolve(), args.split)


if __name__ == "__main__":
    main()
