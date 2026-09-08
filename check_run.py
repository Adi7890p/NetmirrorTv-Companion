import urllib.request
import json
import time

def check_status():
    time.sleep(15)
    url = "https://api.github.com/repos/Adi7890p/helper/actions/runs"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        r = data['workflow_runs'][0]
        print(f"Run #{r.get('run_number')} | Status: {r.get('status')} | Conclusion: {r.get('conclusion')} | Title: {r.get('display_title')}")
        print(f"HTML URL: {r.get('html_url')}")

if __name__ == '__main__':
    check_status()
