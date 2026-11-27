"""Permit one upstream backoff only on an explicit status for the same Central request."""
from pathlib import Path
import re
import sys

CENTRAL = re.compile(r'''Could not (?:GET|HEAD)\s+['"]https://repo\.maven\.apache\.org/maven2/[^\s'"]+['"]\.?\s+Received status code 429\b''')
FATAL = re.compile(r'Dependency verification failed|B368_ACTUAL_ORIGIN_REFUSED:|No matching variant|no compatible variant|NDK not configured|No version of NDK matched|Failed to find target',re.I)
REQUEST = re.compile(r'''Could not (?:GET|HEAD)\s+['"]https?://[^\s'"]+['"]\.?\s+Received status code [1-5]\d\d\b''')


def retryable(text):
    requests=REQUEST.findall(text)
    if not requests or FATAL.search(text) or not all(CENTRAL.fullmatch(x) for x in requests):
        return False
    # Do not retry alongside a distinct transport failure, or guess a longer server-supplied delay.
    if re.search(r'PKIX|handshake|timed out|connection (?:reset|refused)|unknown host',text,re.I):
        return False
    headers=re.findall(r'(?im)\bretry-after\s*[:=]\s*([^\r\n]+)',text)
    return all(re.fullmatch(r'[0-9]{1,3}',v.strip()) and int(v)<=65 for v in headers)


if __name__=='__main__':
    assert __debug__ and len(sys.argv)==2
    p=Path(sys.argv[1]); assert p.is_file() and p.stat().st_size<=16*2**20
    raise SystemExit(0 if retryable(p.read_text(encoding='utf8',errors='replace')) else 1)
