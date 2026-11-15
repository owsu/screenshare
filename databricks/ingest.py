# Databricks notebook source
# Upload an API NDJSON export to a Unity Catalog volume, then run this notebook.
# No credentials or precise location data are embedded in this source.
from delta.tables import DeltaTable
from pyspark.sql.types import (
    StructType, StructField, StringType, LongType, DoubleType,
)

dbutils.widgets.text("source_path", "/Volumes/catalog/schema/volume/shadowtrace/*.ndjson")
dbutils.widgets.text("target_table", "catalog.schema.shadowtrace_observations")

schema = StructType([
    StructField(name, kind(), True) for name, kind in [
        ("id", LongType), ("batch_id", LongType), ("device_id", StringType),
        ("session_id", StringType), ("bssid", StringType), ("timestamp_us", LongType),
        ("observed_at", DoubleType), ("latitude", DoubleType), ("longitude", DoubleType),
        ("accuracy_m", DoubleType), ("ssid", StringType), ("rssi_dbm", LongType),
        ("frequency_mhz", LongType), ("capabilities", StringType), ("usable", LongType),
    ]
])
source = spark.read.schema(schema).option("mode", "FAILFAST").json(dbutils.widgets.get("source_path"))
keys = ["device_id", "session_id", "bssid", "timestamp_us"]
source = source.dropDuplicates(keys)
target = dbutils.widgets.get("target_table")
# Validate identifiers before using the configured table name.
import re
if not re.fullmatch(r"[A-Za-z_]\w*\.[A-Za-z_]\w*\.[A-Za-z_]\w*", target):
    raise ValueError("Use a catalog.schema.table identifier")
if not spark.catalog.tableExists(target):
    source.write.format("delta").saveAsTable(target)
else:
    (DeltaTable.forName(spark,target).alias("t").merge(
        source.alias("s"), " AND ".join(f"t.{key} = s.{key}" for key in keys)
    ).whenNotMatchedInsertAll().execute())
