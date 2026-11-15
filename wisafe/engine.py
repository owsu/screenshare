"""Conservative heuristic; scores measure evidence, not probability or intent."""
import json
import math
import time

from .models import ScanBatch
from .store import Store

FRESH_SECONDS = 30
COVERAGE_SECONDS = 60
HALF_LIFE_SECONDS = 3600
MAX_LINK_SECONDS = 1800
TRANSITION_METERS = 200


def distance(a, b):
    lat1, lat2 = math.radians(a["latitude"]), math.radians(b["latitude"])
    dl = math.radians(b["longitude"] - a["longitude"])
    h = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(dl/2)**2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(h)))


def decay(score, seconds):
    return score * 2 ** (-max(0, seconds) / HALF_LIFE_SECONDS)


class Detector:
    def __init__(self, store: Store):
        self.store = store

    def ingest(self, batch: ScanBatch, now=None):
        now = time.time() if now is None else now
        if batch.captured_at > now + 60:
            raise ValueError("Capture time is in the future")
        with self.store.connect() as db:
            # Serialize read/modify/write across concurrent API requests.
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute(
                "SELECT payload FROM batches WHERE device_id=? AND session_id=? AND elapsed_us=?",
                (batch.device_id, batch.session_id, batch.elapsed_us),
            ).fetchone()
            if existing:
                if existing["payload"] != batch.model_dump_json():
                    raise ValueError("Batch identity reused with different contents")
                return {"duplicate": True, "fresh_observations": 0}
            device = db.execute("SELECT * FROM devices WHERE device_id=?", (batch.device_id,)).fetchone()
            if device and batch.captured_at <= device["last_at"]:
                raise ValueError("Batches must be uploaded in capture order")
            cursor = db.execute(
                "INSERT INTO batches(device_id,session_id,captured_at,elapsed_us,payload) VALUES(?,?,?,?,?)",
                (batch.device_id, batch.session_id, batch.captured_at, batch.elapsed_us, batch.model_dump_json()),
            )
            batch_id = cursor.lastrowid
            location = batch.location.model_dump() if batch.location else None
            previous = json.loads(device["location"]) if device and device["location"] else None
            valid = bool(location and location["accuracy_m"] <= 50 and location["age_ms"] <= 15000)
            location_status = "ready" if valid else "missing" if not location else "inaccurate" if location["accuracy_m"] > 50 else "stale"
            moving = False
            if valid:
                location["at"] = batch.captured_at - location["age_ms"] / 1000
                if previous:
                    dt = location["at"] - previous["at"]
                    net = max(0, distance(previous, location) - previous["accuracy_m"] - location["accuracy_m"])
                    # GPS displacement must agree with independent speed when provided.
                    speed = location["speed_mps"]
                    # A reused but recent fix is still valid for a new Wi-Fi scan.
                    # It cannot establish new motion on its own.
                    valid = (dt > 0 and net / dt <= 65) or (dt == 0 and distance(previous,location) < 1)
                    if speed is not None and dt > 0 and net > max(30, (speed + 3) * dt):
                        valid = False
                    moving = valid and dt > 0 and net >= 10 and net / dt >= 0.5
                if location["speed_mps"] is not None:
                    moving = valid and location["speed_mps"] >= 0.5
                if not valid:
                    location_status = "inconsistent_motion"
            active = valid and moving and batch.mode != "passive"
            mode = "active" if active else "passive"
            fresh = 0
            usable_fresh = 0
            candidates = []
            for ap in batch.access_points:
                age = (batch.elapsed_us - ap.timestamp_us) / 1e6
                if not 0 <= age <= FRESH_SECONDS:
                    continue
                # Do not pair an old scan with a substantially later location fix.
                usable = valid and abs(age - location["age_ms"] / 1000) <= 10
                observed_at = batch.captured_at - age
                inserted = db.execute(
                    """INSERT OR IGNORE INTO observations
                    (batch_id,device_id,session_id,bssid,timestamp_us,observed_at,
                    latitude,longitude,accuracy_m,ssid,rssi_dbm,frequency_mhz,capabilities,usable)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (batch_id,batch.device_id,batch.session_id,ap.bssid,ap.timestamp_us,observed_at,
                     location["latitude"] if usable else None, location["longitude"] if usable else None,
                     location["accuracy_m"] if usable else None,ap.ssid,ap.rssi_dbm,ap.frequency_mhz,
                     ap.capabilities,int(usable)),
                ).rowcount
                if not inserted:
                    continue
                fresh += 1
                # Visibility is independent of GPS readiness and never earns a score.
                db.execute("INSERT OR IGNORE INTO tracks(device_id,bssid,updated_at) VALUES(?,?,?)",
                           (batch.device_id,ap.bssid,batch.captured_at))
                if not usable:
                    continue
                usable_fresh += 1
                track = db.execute("SELECT * FROM tracks WHERE device_id=? AND bssid=?",
                                   (batch.device_id,ap.bssid)).fetchone()
                anchor = json.loads(track["anchor"]) if track["anchor"] else None
                point = dict(location, at=observed_at)
                if anchor is None or observed_at - anchor["at"] > MAX_LINK_SECONDS or not active:
                    # Passive sightings reset the anchor so idle time cannot earn movement evidence.
                    db.execute("UPDATE tracks SET anchor=? WHERE device_id=? AND bssid=?",
                               (json.dumps(point),batch.device_id,ap.bssid))
                    continue
                net_distance = distance(anchor,point) - anchor["accuracy_m"] - point["accuracy_m"]
                if net_distance >= TRANSITION_METERS and observed_at > anchor["at"]:
                    candidates.append((ap.bssid,track,point,net_distance))
            # Only APs independently making the same transition count as companions.
            companions = len(candidates)
            for bssid, track, point, meters in candidates:
                increment = 15 / max(1, companions)
                reason = "shared_co_movement" if companions > 1 else "co_movement"
                if track["infrastructure"]:
                    increment, reason = 0, "known_infrastructure"
                score = min(100, decay(track["score"],batch.captured_at-track["updated_at"]) + increment)
                db.execute("""UPDATE tracks SET score=?,updated_at=?,anchor=?,transitions=transitions+1
                              WHERE device_id=? AND bssid=?""",
                           (score,batch.captured_at,json.dumps(point),batch.device_id,bssid))
                db.execute("""INSERT INTO evidence(batch_id,device_id,bssid,at,distance_m,increment,companions,reason)
                              VALUES(?,?,?,?,?,?,?,?)""",
                           (batch_id,batch.device_id,bssid,batch.captured_at,meters,increment,companions,reason))
            last_fresh = batch.captured_at if fresh else (device["last_fresh_at"] if device else None)
            coverage = "observing" if usable_fresh else "insufficient_monitoring_coverage"
            db.execute("INSERT INTO batch_diagnostics VALUES(?,?,?,?)",
                       (batch_id,location_status,fresh,usable_fresh))
            kept_location = location if valid else previous
            db.execute("""INSERT OR REPLACE INTO devices VALUES(?,?,?,?,?,?)""",
                       (batch.device_id,batch.captured_at,last_fresh,json.dumps(kept_location) if kept_location else None,mode,coverage))
            return {"duplicate": False, "fresh_observations": fresh, "mode": mode,
                    "location_accepted": valid, "location_status": location_status,
                    "coverage": coverage, "transitions_added": companions}

    def status(self, device_id, now=None):
        now = time.time() if now is None else now
        with self.store.connect() as db:
            device = db.execute("SELECT * FROM devices WHERE device_id=?",(device_id,)).fetchone()
            coverage = "insufficient_monitoring_coverage"
            if device and device["last_fresh_at"] and now-device["last_fresh_at"] <= COVERAGE_SECONDS:
                coverage = device["coverage"]
            tracks = []
            for row in db.execute("SELECT * FROM tracks WHERE device_id=?",(device_id,)):
                latest = db.execute("SELECT * FROM observations WHERE device_id=? AND bssid=? ORDER BY id DESC LIMIT 1",
                                    (device_id,row["bssid"])).fetchone()
                named = db.execute("""SELECT ssid FROM observations WHERE device_id=? AND bssid=?
                    AND TRIM(ssid)<>'' AND LOWER(ssid)<>'<unknown ssid>' ORDER BY id DESC LIMIT 1""",
                    (device_id,row["bssid"])).fetchone()
                score = decay(row["score"],now-row["updated_at"])
                level = "repeated_co_movement" if score >= 30 else "possible_co_movement" if score >= 10 else "low_evidence"
                tracks.append({"bssid":row["bssid"],"score":round(score,2),"level":level,
                               "transitions":row["transitions"],"known_infrastructure":bool(row["infrastructure"]),
                               "ssid":named["ssid"] if named else "",
                               "ssid_is_historical":bool(named and latest and named["ssid"] != latest["ssid"]),
                               "rssi_dbm":latest["rssi_dbm"] if latest else None,
                               "last_seen_at":latest["observed_at"] if latest else None,
                               "location_ready":bool(latest and latest["usable"])})
            diagnostics = db.execute("""SELECT b.captured_at,d.location_status,d.fresh_count,d.usable_count
                FROM batches b LEFT JOIN batch_diagnostics d ON d.batch_id=b.id
                WHERE b.device_id=? ORDER BY b.id DESC LIMIT 1""",(device_id,)).fetchone()
            return {"device_id":device_id,"coverage":coverage,"mode":device["mode"] if device else "unknown",
                    "diagnostics":dict(diagnostics) if diagnostics else None,
                    "last_fresh_at":device["last_fresh_at"] if device else None,
                    "interpretation":"Co-movement evidence cannot establish malicious intent or guarantee safety.",
                    "tracks":sorted(tracks,key=lambda t:t["score"],reverse=True)}
