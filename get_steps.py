import urllib.request
import json

def get_job_steps():
    url = "https://api.github.com/repos/Adi7890p/helper/actions/runs/34227504313/jobs"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        job = data['jobs'][0]
        print(f"Job: {job['name']} | Conclusion: {job['conclusion']}")
        for s in job['steps']:
            print(f"  Step: {s['name']} -> {s['conclusion']} ({s.get('number')})")

if __name__ == '__main__':
    get_job_steps()
