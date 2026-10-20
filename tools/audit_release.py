"""Check an export for accidental runtime data and secret patterns; no network.

Personal identifiers belong in a private, untracked JSON pattern file, never here.
This lightweight check does not replace archive/history and manual review.
"""
import argparse,json,re,zipfile
from pathlib import Path

TEXT={'.java','.html','.yaml','.yml','.md','.properties','.ps1','.cmd','.json','.kts','.gradle','.xml','.py','.txt','.sql','.cjs','.js','.sh'}
PATTERNS=[r'sk-[A-Za-z0-9_-]{28,}',r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']
def inspect(root,private_patterns=()):
    issues=[];count=0
    for f in root.rglob('*'):
        if not f.is_file() or any(p in {'.git','.work','.gradle','build','dist','__pycache__'} for p in f.relative_to(root).parts):continue
        rel=f.relative_to(root).as_posix();count+=1
        if any(rel.startswith(prefix) for prefix in ['config/','logs/','data/','db/','app/data/','app/db/','get_jobs/data/','get_jobs/db/','get_jobs/cookies/']) or f.suffix in {'.db','.sqlite','.pid','.log'} or f.name in {'auth.json','launcher.json','process.json','.env'}:issues.append({'file':rel,'reason':'Runtime data or credentials must not be distributed'})
        if f.suffix.lower() in TEXT:
            text=f.read_text(encoding='utf-8-sig',errors='replace')
            if any(re.search(pattern,text,re.I) for pattern in [*PATTERNS,*private_patterns]):issues.append({'file':rel,'reason':'Possible secret or private material; review privately'})
    return {'passed':not issues,'filesChecked':count,'issues':issues,'scope':'Pattern/path check, not a guarantee against every kind of secret'}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--private-patterns',type=Path,help='Private JSON list of regex strings; keep outside the export');a=p.parse_args()
    patterns=json.loads(a.private_patterns.read_text(encoding='utf-8-sig')) if a.private_patterns else []
    if not isinstance(patterns,list) or not all(isinstance(x,str) for x in patterns):p.error('Private patterns must be a JSON list of strings')
    report=inspect(a.root,patterns);print(json.dumps(report,ensure_ascii=False));raise SystemExit(0 if report['passed'] else 1)
