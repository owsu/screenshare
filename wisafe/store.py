import sqlite3
import os
from contextlib import contextmanager
from pathlib import Path


def default_db_path():
    # An upgrade keeps the existing database; new installations use the new name.
    return os.getenv("WISAFE_DB") or os.getenv("SHADOWTRACE_DB") or (
        "data/shadowtrace.sqlite3" if Path("data/shadowtrace.sqlite3").exists() else "data/wisafe.sqlite3")


SCHEMA = """
CREATE TABLE IF NOT EXISTS batches (
 id INTEGER PRIMARY KEY, device_id TEXT NOT NULL, session_id TEXT NOT NULL,
 captured_at REAL NOT NULL, elapsed_us INTEGER NOT NULL, payload TEXT NOT NULL,
 UNIQUE(device_id, session_id, elapsed_us)
);
CREATE TABLE IF NOT EXISTS observations (
 id INTEGER PRIMARY KEY, batch_id INTEGER NOT NULL REFERENCES batches(id),
 device_id TEXT NOT NULL, session_id TEXT NOT NULL, bssid TEXT NOT NULL,
 timestamp_us INTEGER NOT NULL, observed_at REAL NOT NULL,
 latitude REAL, longitude REAL, accuracy_m REAL,
 ssid TEXT NOT NULL, rssi_dbm INTEGER NOT NULL, frequency_mhz INTEGER NOT NULL,
 capabilities TEXT NOT NULL, usable INTEGER NOT NULL,
 UNIQUE(device_id, session_id, bssid, timestamp_us)
);
CREATE INDEX IF NOT EXISTS observation_device ON observations(device_id, id);
CREATE INDEX IF NOT EXISTS observation_network ON observations(device_id,bssid,id);
CREATE TABLE IF NOT EXISTS batch_diagnostics (
 batch_id INTEGER PRIMARY KEY REFERENCES batches(id),
 location_status TEXT NOT NULL, fresh_count INTEGER NOT NULL, usable_count INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
 device_id TEXT PRIMARY KEY, last_at REAL NOT NULL, last_fresh_at REAL,
 location TEXT, mode TEXT NOT NULL, coverage TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tracks (
 device_id TEXT NOT NULL, bssid TEXT NOT NULL, score REAL NOT NULL DEFAULT 0,
 updated_at REAL NOT NULL, anchor TEXT, transitions INTEGER NOT NULL DEFAULT 0,
 infrastructure INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(device_id,bssid)
);
CREATE TABLE IF NOT EXISTS evidence (
 id INTEGER PRIMARY KEY, batch_id INTEGER NOT NULL REFERENCES batches(id),
 device_id TEXT NOT NULL, bssid TEXT NOT NULL, at REAL NOT NULL,
 distance_m REAL NOT NULL, increment REAL NOT NULL, companions INTEGER NOT NULL,
 reason TEXT NOT NULL
);
"""


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        try:
            with connection:
                yield connection
        finally:
            connection.close()
