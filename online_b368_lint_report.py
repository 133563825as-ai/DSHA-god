"""Summarize only a new, bounded AGP lint XML report; never emit issue bodies."""
import hashlib
import json
from pathlib import Path
import re
import subprocess as sp
import sys
from xml.etree import ElementTree as ET


# XmlWriter's REPORT mode uses Severity.getDescription(), not the enum name.
SEVERITIES={'Fatal':'errors','Error':'errors','Warning':'warnings','Hint':'other','Ignore':'other'}


def counts(body):
    assert 0 < len(body) <= 16 * 2**20, 'SIZE'
    assert b'\x00' not in body and b'<!DOCTYPE' not in body and b'<!ENTITY' not in body, 'UNSAFE'
    body.decode('utf8', errors='strict')
    root=ET.fromstring(body)
    assert root.tag=='issues' and root.get('format')=='6', 'ROOT'
    result={'errors':0,'warnings':0,'other':0}
    for issue in root:
        assert issue.tag=='issue' and issue.get('id'), 'ISSUE'
        severity=issue.get('severity')
        assert severity in SEVERITIES, 'SEVERITY'
        result[SEVERITIES[severity]]+=1
    assert sum(result.values())<=100000, 'COUNT'
    return result


def bounded(root, relative, limit):
    assert type(relative) is str and 0 < len(relative) <= 512
    p=root/relative
    assert p.is_relative_to(root) and p.resolve()==p
    assert p.is_file() and p.stat().st_size<=limit
    return p.read_bytes()


def public_first_error(body, src):
    first=next((issue for issue in ET.fromstring(body) if SEVERITIES[issue.get('severity')]=='errors'),None)
    if first is None:
        return 'NONE', None
    # Reconstruct the value from Git's immutable public tree, not from report text.
    location=first.find('location')
    m=location.get('file','') if location is not None else ''
    if not m or len(m)>512:
        return 'WITHHELD', None
    path=Path(m)
    if path.is_absolute():
        if not path.is_relative_to(src):return 'WITHHELD', None
        path=path.relative_to(src)
    elif path.parts[:1]==('src',):
        # This graph has one Android project (app); lint may use module-relative paths.
        path=Path('app')/path
    if not re.fullmatch(r'app/src/(?:main|standard|rebirthTrial)/[A-Za-z0-9_./-]{1,220}\.(?:kt|java|xml)',path.as_posix()) or '..' in path.parts:
        return 'WITHHELD', None
    commit='b2ed231b165c90339a297e136e476e98ade67f11'
    cmd=['/usr/bin/git','--no-replace-objects','-C',str(src)]
    if sp.check_output(cmd+['rev-parse','HEAD','HEAD^{tree}'],timeout=10).decode('ascii').split()!=[commit,'7fb60ba40c3e60094c7ff02281a2a4058f6da545']:
        return 'WITHHELD', None
    result=sp.check_output(cmd+['ls-tree','-z',commit,'--',path.as_posix()],timeout=10).decode('ascii')
    row=re.fullmatch(r'100(?:644|755) blob ([a-f0-9]{40})\t([A-Za-z0-9_./-]+)\x00',result)
    if not row or row[2]!=path.as_posix():return 'WITHHELD', None
    line=location.get('line','')
    if not re.fullmatch(r'[1-9][0-9]{0,5}',line):return 'WITHHELD', None
    public=sp.check_output(cmd+['cat-file','blob',row[1]],timeout=10)
    f=src/path
    if int(line)>len(public.splitlines()) or not f.is_file() or f.resolve()!=f or f.stat().st_size!=len(public) or f.read_bytes()!=public:
        return 'WITHHELD', None
    return 'PUBLIC', f'{row[2]}:{int(line)}'


def main():
    assert __debug__ and len(sys.argv) in (3,4)
    root, plan=map(Path,sys.argv[1:3]); assert root.resolve()==root
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
    except (AssertionError, UnicodeError, ET.ParseError) as exc:
        code_by_type={UnicodeError:'ENCODING', ET.ParseError:'PARSE'}
        reason=next((v for k,v in code_by_type.items() if isinstance(exc,k)), None)
        if reason is None:
            known={'SIZE','UNSAFE','ROOT','ISSUE','SEVERITY','COUNT'}
            reason=str(exc) if str(exc) in known else 'OTHER'
        print('B368_LINT_REPORT_REFUSED=XML_SCHEMA')
        print('B368_LINT_XML_REASON='+reason)
        return 1
    for key,count in result.items():print(f'B368_LINT_REPORT_COUNT={key}:{count}')
    print('B368_LINT_REPORT_SHA256='+hashlib.sha256(body).hexdigest())
    if len(sys.argv)==4:
        src=Path(sys.argv[3]); assert src.resolve()==src and root==src/'app/build'
        try:
            state,where=public_first_error(body,src)
        except (OSError, UnicodeError, sp.SubprocessError):
            state,where='WITHHELD',None
        print('B368_LINT_FIRST_LOCATION_STATE='+state)
        if where is not None:
            print('B368_LINT_FIRST_PUBLIC_LOCATION='+where)
    return 1 if code!='0' or result['errors'] else 0


if __name__=='__main__': raise SystemExit(main())
