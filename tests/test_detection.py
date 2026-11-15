import tempfile
import unittest
from pathlib import Path

from wisafe.engine import Detector
from wisafe.models import ScanBatch
from wisafe.store import Store


class DetectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(str(Path(self.tmp.name) / "test.sqlite3"))
        self.detector = Detector(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def batch(self, seconds=0, meters=0, count=1, **changes):
        data = dict(device_id="phone", session_id="boot",captured_at=10000+seconds,
                    elapsed_us=int((1000+seconds)*1e6),mode="auto",
                    location=dict(latitude=meters/111195,longitude=0,accuracy_m=5,age_ms=0,speed_mps=1.5),
                    access_points=[dict(bssid=f"aa:bb:cc:dd:ee:{i:02x}",ssid="shared",
                        timestamp_us=int((1000+seconds)*1e6),rssi_dbm=-50,
                        frequency_mhz=2412,capabilities="WPA3") for i in range(count)])
        data.update(changes)
        return ScanBatch(**data)

    def ingest(self, batch):
        return self.detector.ingest(batch,now=batch.captured_at)

    def score(self, at=10400):
        return self.detector.status("phone",now=at)["tracks"][0]["score"]

    def test_movement_reappears_at_three_locations(self):
        self.ingest(self.batch())
        self.ingest(self.batch(200,500))
        first = self.score(10200)
        self.ingest(self.batch(400,1000))
        self.assertEqual(first,15)
        self.assertGreater(self.score(),first)

    def test_library_passive_and_explicit_passive(self):
        self.ingest(self.batch())
        for t in (200,400,600):
            self.ingest(self.batch(t,0))
        self.assertEqual(self.score(10600),0)
        self.ingest(self.batch(800,500,mode="passive"))
        self.assertEqual(self.score(10800),0)

    def test_shared_transit_discount(self):
        self.ingest(self.batch(count=3))
        self.ingest(self.batch(200,500,count=3))
        self.assertEqual(self.score(10200),5)

    def test_bssid_identity_not_ssid(self):
        self.ingest(self.batch(count=2))
        other = self.batch(200,500).model_dump()
        other["access_points"][0]["bssid"] = "aa:bb:cc:dd:ee:99"
        self.ingest(ScanBatch(**other))
        self.assertTrue(all(t["score"]==0 for t in self.detector.status("phone",10400)["tracks"]))

    def test_cached_scan_and_batch_retry(self):
        original = self.batch()
        self.ingest(original)
        self.assertTrue(self.ingest(original)["duplicate"])
        cached = self.batch(10,10).model_dump()
        cached["access_points"] = [p.model_dump() for p in original.access_points]
        self.assertEqual(self.ingest(ScanBatch(**cached))["fresh_observations"],0)
        self.assertEqual(self.detector.status("phone",10010)["coverage"],"insufficient_monitoring_coverage")

    def test_gps_jump_and_bad_accuracy(self):
        self.ingest(self.batch())
        jump = self.batch(10,300).model_dump()
        jump["location"]["speed_mps"] = 0
        self.assertFalse(self.ingest(ScanBatch(**jump))["location_accepted"])
        jump = self.batch(200,500).model_dump()
        jump["location"]["accuracy_m"] = 200
        self.assertFalse(self.ingest(ScanBatch(**jump))["location_accepted"])
        self.assertEqual(self.score(),0)

    def test_decay_restart_and_coverage_timeout(self):
        self.ingest(self.batch())
        self.ingest(self.batch(200,500))
        self.detector = Detector(Store(self.store.path))
        self.assertEqual(self.score(13800),7.5)
        self.assertEqual(self.detector.status("phone",10300)["coverage"],"insufficient_monitoring_coverage")

    def test_security_metadata_has_no_score_effect(self):
        self.ingest(self.batch())
        batch = self.batch(200,500).model_dump()
        batch["access_points"][0]["capabilities"] = "OPEN"
        self.ingest(ScanBatch(**batch))
        self.assertEqual(self.score(10200),15)

    def test_missing_scans_location_and_long_gap(self):
        self.ingest(self.batch())
        self.assertEqual(self.ingest(self.batch(200,500,access_points=[]))["coverage"],"insufficient_monitoring_coverage")
        self.assertFalse(self.ingest(self.batch(400,500,location=None))["location_accepted"])
        self.ingest(self.batch(4000,1000))
        self.assertEqual(self.score(14000),0)

    def test_order_and_future_clock(self):
        self.ingest(self.batch(200,500))
        with self.assertRaises(ValueError):
            self.ingest(self.batch())
        with self.assertRaises(ValueError):
            self.detector.ingest(self.batch(400,1000),now=10000)

    def test_infrastructure_suppresses_score(self):
        self.ingest(self.batch())
        with self.store.connect() as db:
            db.execute("UPDATE tracks SET infrastructure=1")
        self.ingest(self.batch(200,500))
        self.assertEqual(self.score(10200),0)

    def test_stale_and_future_ap_timestamps(self):
        original = self.batch()
        self.ingest(original)
        stale = self.batch(200,500).model_dump()
        stale["access_points"][0]["timestamp_us"] = original.access_points[0].timestamp_us
        self.assertEqual(self.ingest(ScanBatch(**stale))["fresh_observations"],0)
        future = self.batch(400,1000).model_dump()
        future["access_points"][0]["timestamp_us"] += 1
        self.assertEqual(self.ingest(ScanBatch(**future))["fresh_observations"],0)

    def test_duplicate_bssid_in_one_batch_rejected(self):
        batch = self.batch().model_dump()
        batch["access_points"].append(dict(batch["access_points"][0],timestamp_us=999999999))
        with self.assertRaises(ValueError):
            ScanBatch(**batch)

    def test_device_isolation_and_new_session(self):
        self.ingest(self.batch())
        self.ingest(self.batch(200,500,device_id="other"))
        self.assertEqual(self.score(10200),0)
        self.ingest(self.batch(400,1000,session_id="restart"))
        self.assertEqual(self.score(10400),15)

    def test_time_misaligned_scan_not_scored(self):
        self.ingest(self.batch())
        batch = self.batch(200,500).model_dump()
        batch["access_points"][0]["timestamp_us"] -= 20_000_000
        result = self.ingest(ScanBatch(**batch))
        self.assertEqual(result["coverage"],"insufficient_monitoring_coverage")
        self.assertEqual(self.score(10200),0)

    def test_networks_visible_without_gps_but_do_not_score(self):
        result = self.ingest(self.batch(location=None,count=3))
        status = self.detector.status("phone",10000)
        self.assertEqual(len(status["tracks"]),3)
        self.assertEqual(status["diagnostics"]["location_status"],"missing")
        self.assertTrue(all(not row["location_ready"] and row["score"] == 0 for row in status["tracks"]))
        self.assertEqual(result["coverage"],"insufficient_monitoring_coverage")
        self.ingest(self.batch(200,500,count=3))
        self.assertTrue(all(row["transitions"] == 0 for row in self.detector.status("phone",10200)["tracks"]))

    def test_recent_reused_location_fix_accepts_new_scan(self):
        self.ingest(self.batch())
        batch = self.batch(5,0).model_dump()
        batch["location"]["age_ms"] = 5000
        batch["location"]["speed_mps"] = 0
        result = self.ingest(ScanBatch(**batch))
        self.assertTrue(result["location_accepted"])
        self.assertEqual(result["coverage"],"observing")
        self.assertEqual(self.score(10005),0)

    def test_poor_gps_diagnostics_and_metadata(self):
        batch = self.batch().model_dump()
        batch["location"]["accuracy_m"] = 100
        self.ingest(ScanBatch(**batch))
        status = self.detector.status("phone",10000)
        self.assertEqual(status["diagnostics"]["location_status"],"inaccurate")
        self.assertEqual(status["tracks"][0]["ssid"],"shared")
        self.assertEqual(status["tracks"][0]["rssi_dbm"],-50)
        self.assertEqual(status["tracks"][0]["last_seen_at"],10000)

    def test_missing_ssid_keeps_last_known_name_only_for_same_bssid(self):
        first = self.batch().model_dump()
        first["access_points"][0]["ssid"] = "Library Wi-Fi"
        self.ingest(ScanBatch(**first))
        second = self.batch(200,0,count=2).model_dump()
        for ap in second["access_points"]:
            ap["ssid"] = ""
        self.ingest(ScanBatch(**second))
        tracks = {t["bssid"]:t for t in self.detector.status("phone",10200)["tracks"]}
        remembered = tracks["aa:bb:cc:dd:ee:00"]
        unnamed = tracks["aa:bb:cc:dd:ee:01"]
        self.assertEqual(remembered["ssid"],"Library Wi-Fi")
        self.assertTrue(remembered["ssid_is_historical"])
        self.assertEqual(unnamed["ssid"],"")
        self.assertFalse(unnamed["ssid_is_historical"])
        third = self.batch(400,0).model_dump()
        third["access_points"][0]["ssid"] = "Renamed network"
        self.ingest(ScanBatch(**third))
        renamed = next(t for t in self.detector.status("phone",10400)["tracks"] if t["bssid"] == "aa:bb:cc:dd:ee:00")
        self.assertEqual(renamed["ssid"],"Renamed network")
        self.assertFalse(renamed["ssid_is_historical"])


if __name__ == "__main__":
    unittest.main()
