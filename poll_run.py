import urllib.request
import json
import time

def check():
    time.sleep(25)
    url = "https://api.github.com/repos/Adi7890p/helper/actions/runs"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        r = data['workflow_runs'][0]
        print(f"Run #{r.get('run_number')} | Status: {r.get('status')} | Conclusion: {r.get('conclusion')} | Title: {r.get('display_title')}")
        art_url = r.get('artifacts_url')
        if art_url:
            req_art = urllib.request.Request(art_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req_art) as a_resp:
                a_data = json.loads(a_resp.read().decode('utf-8'))
                print("Artifacts count:", a_data.get('total_count'))
                for a in a_data.get('artifacts', []):
                    print(f"  Artifact: {a.get('name')}, Size: {a.get('size_in_bytes')} bytes")

if __name__ == '__main__':
    check()
