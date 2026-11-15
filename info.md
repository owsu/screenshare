Scanning mechanisms in android app for tracking devices considering we have an android application (running expo for now):


Bluetooth Low Energy (BLE) Scanning: react-native-ble-plx (use startDeviceScan with allowDuplicates: true to continuously read raw advertisement packets and RSSI signal strength).

Wi-Fi & Hotspot Scanning: react-native-wifi-reborn (Android-only loadWifiList() / reScanAndLoadWifiList() returns SSID, BSSID, level/RSSI, and frequency).   

GPS Movement Verification: expo-location (essential to confirm you are actually moving; otherwise a stationary neighbor's router or AirTag triggers false stalking alerts).

What Android functionality lets us scan?
Android WifiManager API
android.net.wifi.WifiManager
Official Android framework API for discovering Wi-Fi access points.
startScan()	Requests a Wi-Fi scan
getScanResults()	Returns detected access points
ScanResult.BSSID	Gets the AP's MAC identifier
ScanResult.level	Gets signal strength in dBm
ScanResult.frequency	Gets operating frequency
ScanResult.timestamp	Gets the last observed time since device boot
https://developer.android.com/develop/connectivity/wifi/wifi-scan


----------------------------------

1. Detecting android networks and current geo-location (Android studio / reactive native)
2. Take network metadata including location (Android studio)
3. Put data in the database & databricks (SQLite, FastAPI)
4. Detection algorithm -> Based off of timestamps. (General coding language + sqlite access)
Two different modes. Active Detection: Walking/driving/public transport increases risk of nearby devices over time. And passive mode for idle or being in the same place, doesn't increase threat score (if you were studying at a library and someone sat in the same room as you then you can not deem them a stalker for being in close proximity for a long time). If multiple BSSID's are present "following" over location then it increase threat by less because likely public transport. Threat decays after a while.

BSSID: Unique wifi access point -> prevents false positives
Same BSSID & different geo-location: Potential "stalker"


Android already scans wifi networks & stores it into an internal scan cache which we can access. This can help build the relability of our database since we can read it every 10 seconds.

Android can also grab walkspeed & precise location. 

What can go wrong?
There are a few problems your algorithm must explicitly account for:
Scenario	How WiSafe responds
User and unknown hotspot sit in library for hours	Passive mode; no score increase
User walks 500m and same BSSID reappears	First possible movement transition
User visits a third location and BSSID reappears	Further co-movement evidence
Two hotspots share an SSID but have different BSSIDs	Treat separately
Hotspot changes its BSSID	Do not assume it is the same device
User rides a bus containing a legitimate Wi-Fi hotspot	May flag co-movement, but cannot infer malicious intent
Campus Wi-Fi uses distributed APs	Evaluate legitimate infrastructure as a source of false positives
Android repeatedly returns cached observations	Deduplicate using AP timestamp
GPS jumps 300m while phone is stationary	Reject based on accuracy and motion consistency
No fresh Wi-Fi scans are available	Report insufficient monitoring coverage, not safety

One crucial point: WPA2/WPA3 security doesn't affect the stalking score. It is metadata that can help characterize a network or support a separate Evil Twin detector.

----------------------------------

Build TrailGuard, a privacy-focused android app that periodically scans nearby Bluetooth/hotspot Low Energy advertisements and Wi-Fi access points, stores observations locally, and combines them with GPS data or user-marked checkpoints. Use a Python/FastAPI backend, SQLite, and a React/TypeScript dashboard. Verify hardware capabilities first, and provide clearly labeled replay mode when hardware or location data is unavailable.

Create an explainable co-travel evidence score based on repeated observations across locations, coverage-adjusted duration, movement, and reappearance. Show contributing factors and data quality separately. Account for failed scans, inaccurate GPS, rotating Bluetooth identifiers, and legitimate fellow travelers. Never claim to identify a person or prove stalking; report “possible co-travel” and its limitations. Include device labeling, encounter timelines, alert cooldowns, configurable scan intervals, and data deletion. Keep identifiers pseudonymized locally and do not automatically link devices across protocols.

Make Databricks optional for uploading aggregate features, evaluating detection delays and false alerts, and experimenting with anomaly detection after the deterministic baseline works. Keep live monitoring functional offline, never upload raw identifiers or exact routes by default, and do not interpret anomaly scores as stalking probabilities. Implement the working app, tests, setup instructions, and seeded replay scenarios covering stationary devices, a traveling test beacon, benign co-travel, identifier rotation, and scan/GPS failures. Prioritize a complete, testable end-to-end prototype over extra features.










<!-- 
"""WiSafe: Windows Wi-Fi hotspot co-movement proof of concept.

Usage: python shadowtrace_hotspot.py --ssid WiSafe-Test
Requires Windows, an enabled Wi-Fi adapter, and Wi-Fi scan permissions.
Only scans nearby advertisements; does not connect to any network.
"""

import argparse
import csv
from datetime import datetime
from pathlib import Path
import re
import subprocess

SSID_RE = re.compile(r"^\s*SSID\s+\d+\s*:\s*(.*)$", re.I)
BSSID_RE = re.compile(r"^\s*BSSID\s+\d+\s*:\s*([0-9A-F]{2}(?::[0-9A-F]{2}){5})", re.I)
SIGNAL_RE = re.compile(r"^\s*Signal\s*:\s*(\d+)%", re.I)


def parse_networks(output):
    """Parse English-language 'netsh wlan show networks mode=bssid' output."""
    access_points = []
    current_ssid = None
    current_ap = None
    for line in output.splitlines():
        ssid = SSID_RE.match(line)
        if ssid:
            current_ssid = ssid.group(1).strip()
            current_ap = None
            continue
        bssid = BSSID_RE.match(line)
        if bssid and current_ssid is not None:
            current_ap = {
                "ssid": current_ssid,
                "bssid": bssid.group(1).upper(),
                "signal": "",
            }
            access_points.append(current_ap)
            continue
        signal = SIGNAL_RE.match(line)
        if signal and current_ap is not None:
            current_ap["signal"] = signal.group(1)
    return access_points


def scan_wifi():
    result = subprocess.run(
        ["netsh", "wlan", "show", "networks", "mode=bssid"],
        capture_output=True,
        text=True,
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError((result.stdout + "\n" + result.stderr).strip())
    return parse_networks(result.stdout)


def jaccard(a, b):
    union = a | b
    return len(a & b) / len(union) if union else 1.0


def main():
    parser = argparse.ArgumentParser(description="Passive Windows hotspot co-movement demo")
    parser.add_argument("--ssid", default="WiSafe-Test", help="Name of your test phone hotspot")
    parser.add_argument("--list", action="store_true", help="List visible access points and quit")
    args = parser.parse_args()

    try:
        initial = scan_wifi()
    except (OSError, RuntimeError) as exc:
        print("Wi-Fi scan failed:", exc)
        print("Check that Wi-Fi is on, the WLAN service is running, and Windows location permissions allow scanning.")
        return

    if args.list:
        for ap in initial:
            print(f"{ap['ssid']:<28} {ap['bssid']}   {ap['signal']}%")
        if not initial:
            print("No BSSIDs found. Check Wi-Fi and location permissions.")
        return

    print(f"Tracking demo hotspot SSID: {args.ssid!r}")
    print("Carry your laptop and test phone to physically DIFFERENT Wi-Fi environments.")
    print("Press Enter to scan at each checkpoint; type q and Enter to quit.")
    print("This is a demonstration of co-presence, NOT evidence of stalking.\n")

    target_bssid = None
    environments = []
    log_path = Path("shadowtrace_observations.csv")
    with log_path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file)
        writer.writerow([
            "timestamp", "ssid", "tracked_bssid", "visible", "signal_percent",
            "other_access_points", "distinct_environments", "assessment" -->