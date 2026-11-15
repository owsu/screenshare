import hmac
import json
import os
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import StreamingResponse

from .engine import Detector
from .models import AccessPoint, InfrastructureLabel, ScanBatch
from .store import Store, default_db_path


def create_app(db_path=None, api_key=None):
    store = Store(db_path or default_db_path())
    detector = Detector(store)

    def authorize(authorization: Annotated[str | None, Header()] = None):
        expected = api_key or os.getenv("WISAFE_API_KEY") or os.getenv("SHADOWTRACE_API_KEY")
        if not expected:
            raise HTTPException(503, "Set WISAFE_API_KEY before using the API")
        if not authorization or not hmac.compare_digest(authorization.encode(), ("Bearer " + expected).encode()):
            raise HTTPException(401, "Invalid bearer token")

    app = FastAPI(title="WiSafe", version="0.2.0", dependencies=[Depends(authorize)])

    @app.post("/v1/scans")
    def ingest(batch: ScanBatch):
        try:
            return detector.ingest(batch)
        except ValueError as error:
            raise HTTPException(409, str(error)) from error

    @app.get("/v1/devices/{device_id}/status")
    def status(device_id: str):
        return detector.status(device_id)

    @app.get("/v1/devices/{device_id}/evidence")
    def evidence(device_id: str, after_id: int = Query(0, ge=0), limit: int = Query(100, ge=1, le=1000)):
        with store.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT * FROM evidence WHERE device_id=? AND id>? ORDER BY id LIMIT ?",
                (device_id, after_id, limit))]

    @app.put("/v1/devices/{device_id}/infrastructure/{bssid}")
    def label(device_id: str, bssid: str, body: InfrastructureLabel):
        try:
            canonical = AccessPoint(bssid=bssid,timestamp_us=1,rssi_dbm=-50,frequency_mhz=2412).bssid
        except ValueError as error:
            raise HTTPException(422, "Invalid BSSID") from error
        with store.connect() as db:
            # Labeling clears accumulated evidence score; historical events remain auditable.
            changed = db.execute("UPDATE tracks SET infrastructure=?,score=0,anchor=NULL WHERE device_id=? AND bssid=?",
                                 (int(body.known_infrastructure),device_id,canonical)).rowcount
        if not changed:
            raise HTTPException(404, "BSSID has not been observed")
        return {"bssid":canonical, **body.model_dump()}

    @app.get("/v1/devices/{device_id}/export")
    def export(device_id: str, after_id: int = Query(0,ge=0), limit: int = Query(10000,ge=1,le=50000)):
        def rows():
            with store.connect() as db:
                for row in db.execute("SELECT * FROM observations WHERE device_id=? AND id>? ORDER BY id LIMIT ?",
                                      (device_id,after_id,limit)):
                    yield json.dumps(dict(row)) + "\n"
        return StreamingResponse(rows(),media_type="application/x-ndjson")

    return app
