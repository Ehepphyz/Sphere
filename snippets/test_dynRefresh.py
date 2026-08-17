# test_telemetry.py
import sys
import time

print("[PYTHON] Starting telemetry simulation...")
for i in range(1, 4):
    print(f"[DATA] Processing packet {i}/3...")
    time.sleep(0.5)

print("[PYTHON] Target completed successfully.")