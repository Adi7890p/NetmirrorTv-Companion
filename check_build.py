import urllib.request
import json
import time

url = "https://api.github.com/repos/Adi7890p/helper/actions/runs"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})

for _ in range(30):
    try:
        resp = urllib.request.urlopen(req)
        data = json.loads(resp.read().decode())
        runs = data.get("workflow_runs", [])
        if runs:
            latest = runs[0]
            print(f"Run #{latest.get('run_number')} | Status: {latest.get('status')} | Conclusion: {latest.get('conclusion')} | URL: {latest.get('html_url')}", flush=True)
            if latest.get("status") == "completed":
                break
    except Exception as e:
        print("API error:", e, flush=True)
    time.sleep(10)
