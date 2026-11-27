"""Summarize only a new, bounded AGP lint XML report; never emit issue bodies."""
import hashlib
import json
from pathlib import Path
import re
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


def bounded(root, relative, limit):
    assert type(relative) is str and 0 < len(relative) <= 512
    p=root/relative
    assert p.is_relative_to(root) and p.resolve()==p
    assert p.is_file() and p.stat().st_size<=limit
    return p.read_bytes()


def main():
    assert __debug__ and len(sys.argv)==3
    root, plan=map(Path,sys.argv[1:]); assert root.resolve()==root
    try:
        paths=json.loads(bounded(plan.parent,plan.name,4096)); assert set(paths)=={'status','text','xml'}
    except (AssertionError, OSError, ValueError):
        print('B368_LINT_REPORT_REFUSED=DECLARATION'); return 1
    try:
        code=bounded(root,paths['status'],8).decode('ascii')
        assert re.fullmatch('[0-7]',code)
    except (AssertionError, OSError, UnicodeError):
        print('B368_LINT_REPORT_REFUSED=TOOL_STATUS'); return 1
    print('B368_LINT_TOOL_STATUS='+code)
    # Do not replace XML acceptance with a text-only result.
    try:
        tx=bounded(root,paths['text'],16*2**20)
        rows=re.findall(rb'(?m)^([0-9]{1,6}) errors?, ([0-9]{1,6}) warnings?\r?$',tx)
        if len(rows)==1:
            e,w=rows[0]; print('B368_LINT_TEXT_COUNTERS='+e.decode()+':'+w.decode())
    except (AssertionError, OSError):
        print('B368_LINT_TEXT=UNAVAILABLE')
    if paths['xml'] is None:
        print('B368_LINT_REPORT_REFUSED=XML_DISABLED'); return 1
    try:
        body=bounded(root,paths['xml'],16*2**20)
    except (AssertionError, OSError):
        print('B368_LINT_REPORT_REFUSED=XML_MISSING_OR_UNSAFE'); return 1
    try:
        result=counts(body)
    except (AssertionError, UnicodeError, ET.ParseError):
        print('B368_LINT_REPORT_REFUSED=XML_SCHEMA'); return 1
    for key,count in result.items():print(f'B368_LINT_REPORT_COUNT={key}:{count}')
    print('B368_LINT_REPORT_SHA256='+hashlib.sha256(body).hexdigest())
    return 1 if code!='0' or result['errors'] else 0


if __name__=='__main__': raise SystemExit(main())
