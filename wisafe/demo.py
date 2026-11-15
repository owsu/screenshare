"""Run a deterministic three-location scenario without a phone or server."""
import json
import tempfile
from pathlib import Path
from .engine import Detector
from .models import ScanBatch
from .store import Store


def main():
    with tempfile.TemporaryDirectory() as folder:
        detector = Detector(Store(str(Path(folder)/"demo.sqlite3")))
        for seconds, meters in [(0,0),(200,500),(400,1000),(600,1000)]:
            batch = ScanBatch(
                device_id="demo",session_id="demo-run",captured_at=10000+seconds,
                elapsed_us=(1000+seconds)*1_000_000,
                location=dict(latitude=meters/111195,longitude=0,accuracy_m=5,
                              age_ms=0,speed_mps=0 if seconds==600 else 2.5),
                access_points=[dict(bssid="aa:bb:cc:dd:ee:01",ssid="Example",
                    timestamp_us=(1000+seconds)*1_000_000,rssi_dbm=-55,frequency_mhz=2412)],
            )
            outcome = detector.ingest(batch,now=batch.captured_at)
            status = detector.status("demo",now=batch.captured_at)
            print(json.dumps({"meters":meters,"mode":outcome["mode"],"tracks":status["tracks"]}))
        print("One hour later:",json.dumps(detector.status("demo",now=14200)))


if __name__ == "__main__":
    main()
