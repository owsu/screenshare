import tempfile
import time
import unittest
from pathlib import Path
from fastapi.testclient import TestClient
from wisafe.api import create_app


class ApiTests(unittest.TestCase):
    def test_ingest_auth_export_and_label(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(str(Path(tmp)/"api.sqlite3"),api_key="test-secret"))
            self.assertEqual(client.get("/v1/devices/p/status").status_code,401)
            client.headers["Authorization"] = "Bearer test-secret"
            now = time.time()
            payload = dict(device_id="p",session_id="b",captured_at=now,elapsed_us=1000000,
                location=dict(latitude=0,longitude=0,accuracy_m=5,age_ms=0,speed_mps=1),
                access_points=[dict(bssid="aa:bb:cc:dd:ee:01",timestamp_us=1000000,
                                    rssi_dbm=-50,frequency_mhz=2412)])
            self.assertEqual(client.post("/v1/scans",json=payload).status_code,200)
            self.assertTrue(client.post("/v1/scans",json=payload).json()["duplicate"])
            exported = client.get("/v1/devices/p/export")
            self.assertEqual(len(exported.text.splitlines()),1)
            self.assertEqual(client.get("/v1/devices/other/export").text,"")
            response = client.put("/v1/devices/p/infrastructure/aa:bb:cc:dd:ee:01",
                                  json={"known_infrastructure":True})
            self.assertEqual(response.status_code,200)
            self.assertTrue(client.get("/v1/devices/p/status").json()["tracks"][0]["known_infrastructure"])
            payload["location"]["latitude"] = 999
            self.assertEqual(client.post("/v1/scans",json=payload).status_code,422)
