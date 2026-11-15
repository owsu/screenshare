"""Export SQLite observations as NDJSON, optionally upload to a Databricks volume."""
import argparse
import json
from pathlib import Path
from .store import Store, default_db_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default=default_db_path())
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--after-id", type=int, default=0)
    parser.add_argument("--volume-path", help="Optional /Volumes/catalog/schema/volume/export.ndjson")
    args = parser.parse_args()
    if not Path(args.db).is_file():
        parser.error("Database does not exist")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    count, last_id = 0, args.after_id
    with Store(args.db).connect() as db, output.open("w", encoding="utf-8") as target:
        for row in db.execute("SELECT * FROM observations WHERE device_id=? AND id>? ORDER BY id",
                              (args.device,args.after_id)):
            target.write(json.dumps(dict(row))+"\n")
            count += 1
            last_id = row["id"]
    if args.volume_path:
        if not args.volume_path.startswith("/Volumes/") or ".." in args.volume_path.split("/"):
            parser.error("Use an absolute /Volumes/ path")
        from databricks.sdk import WorkspaceClient
        with output.open("rb") as source:
            WorkspaceClient().files.upload(args.volume_path,source,overwrite=True)
    print(json.dumps({"rows":count,"last_id":last_id,"output":str(output)}))


if __name__ == "__main__":
    main()
