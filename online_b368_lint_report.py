"""Summarize only a new, bounded AGP lint XML report; never emit issue bodies."""
import hashlib
from pathlib import Path
import sys
from xml.etree import ElementTree as ET


def counts(body):
    assert 0 < len(body) <= 16 * 2**20
    assert b'\x00' not in body and b'<!DOCTYPE' not in body and b'<!ENTITY' not in body
    body.decode('utf8', errors='strict')
    root=ET.fromstring(body)
    assert root.tag=='issues' and root.get('format')=='6'
    result={'errors':0,'warnings':0,'other':0}
    for issue in root:
        assert issue.tag=='issue' and issue.get('id')
        severity=issue.get('severity')
        assert severity in ('Fatal','Error','Warning','Informational','Ignore')
        key={'Fatal':'errors','Error':'errors','Warning':'warnings'}.get(severity,'other')
        result[key]+=1
    assert sum(result.values())<=100000
    return result


def main():
    assert __debug__ and len(sys.argv)==2
    p=Path(sys.argv[1]); assert p.resolve()==p and p.is_file() and p.stat().st_size<=16*2**20
    body=p.read_bytes(); result=counts(body)
    for key,count in result.items():print(f'B368_LINT_REPORT_COUNT={key}:{count}')
    print('B368_LINT_REPORT_SHA256='+hashlib.sha256(body).hexdigest())
    return 1 if result['errors'] else 0


if __name__=='__main__': raise SystemExit(main())
