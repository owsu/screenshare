from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Location(Model):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_m: float = Field(gt=0, le=10000)
    age_ms: int = Field(ge=0)
    speed_mps: float | None = Field(default=None, ge=0, le=100)


class AccessPoint(Model):
    bssid: str = Field(pattern=r"(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
    ssid: str = Field(default="", max_length=128)
    timestamp_us: int = Field(gt=0)
    rssi_dbm: int = Field(ge=-127, le=0)
    frequency_mhz: int = Field(ge=2000, le=75000)
    capabilities: str = Field(default="", max_length=512)

    @field_validator("bssid")
    @classmethod
    def canonical_bssid(cls, value: str) -> str:
        value = value.lower()
        if value in {"00:00:00:00:00:00", "02:00:00:00:00:00", "ff:ff:ff:ff:ff:ff"}:
            raise ValueError("Unavailable or redacted BSSID")
        return value


class ScanBatch(Model):
    device_id: str = Field(min_length=1, max_length=100, pattern=r"^[\w-]+$")
    # A new UUID for each collector run prevents uptime collisions across reboots.
    session_id: str = Field(min_length=1, max_length=100, pattern=r"^[\w-]+$")
    captured_at: float = Field(gt=0, description="Unix seconds, UTC")
    elapsed_us: int = Field(gt=0, description="Android elapsedRealtimeNanos / 1000")
    mode: Literal["auto", "active", "passive"] = "auto"
    location: Location | None = None
    access_points: list[AccessPoint] = Field(default_factory=list, max_length=512)

    @model_validator(mode="after")
    def unique_access_points(self):
        if len({ap.bssid for ap in self.access_points}) != len(self.access_points):
            raise ValueError("Only one observation per BSSID is allowed in a batch")
        return self


class InfrastructureLabel(Model):
    known_infrastructure: bool
