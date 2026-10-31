"""ET7 comparison of the immutable ET0 inventory against the current app PU receipt."""
import argparse
import json
import hashlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--baseline", default="docs/plans/entity-types-appdev-first-et0-inventory.json")
parser.add_argument("--current", default="outputs/et7/entity-inventory.json")
parser.add_argument("--output", default="outputs/et7/publication-comparison.json")
args = parser.parse_args()
old = {row["type"]: row for row in json.loads(Path(args.baseline).read_text(encoding="utf-8-sig"))["types"]}
new = {row["type"]: row for row in json.loads(Path(args.current).read_text(encoding="utf-8-sig"))["types"]}
# Explicit reviewed parallel report-composition removal; each removed type must also have had no publication.
removed_stores = {"org.ipro.reportstudio.dom." + name for name in ["ReportTemplate", "ReportBand", "ReportField", "ReportOrder", "ReportParam"]}
keys = ["jpaName", "exposure", "reads", "writes", "form", "list", "selection", "sections", "sectionOwner", "ownershipOverrides", "query", "rest", "persistentAttributes", "subsystem"]
changes = []
errors = []
for name in sorted(old.keys() - new.keys()):
    row = old[name]
    published = {key: row.get(key) for key in ["reads", "writes", "form", "list", "selection", "query", "rest"] if row.get(key)}
    allowed = name in removed_stores and not published and row["exposure"] == "INTERNAL_STORE"
    changes.append({"type": name, "change": "removed", "allowed": allowed, "reason": "parallel report composition removal; unpublished owner storage" if allowed else "unreviewed removal"})
    if not allowed: errors.append(changes[-1])
for name in sorted(new.keys() - old.keys()): errors.append({"type": name, "change": "unreviewed addition"})
for name in sorted(old.keys() & new.keys()):
    for key in keys:
        if old[name].get(key) != new[name].get(key):
            change = {"type": name, "property": key, "before": old[name].get(key), "after": new[name].get(key)}
            changes.append(change)
            errors.append(change)
result = {"baseline": args.baseline, "baselineSha256": hashlib.sha256(Path(args.baseline).read_bytes()).hexdigest(), "current": args.current, "currentSha256": hashlib.sha256(Path(args.current).read_bytes()).hexdigest(), "baselineTypes": len(old), "currentTypes": len(new), "commonTypes": len(old.keys() & new.keys()), "comparedProperties": keys, "changes": changes, "unexplainedChanges": errors, "passed": not errors}
Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({k: v for k, v in result.items() if k not in ["changes", "comparedProperties"]}, ensure_ascii=False))
raise SystemExit(1 if errors else 0)
