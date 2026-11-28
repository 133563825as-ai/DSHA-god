"""Summarize a failed full JVM invocation without publishing its private reports."""
import ast
import hashlib
import json
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET


def summarize(p):
    repo=p/'receiver'
    reports=repo/'app/build/test-results/testStandardDebugUnitTest'
    plan=json.loads((p/'unit-plan.json').read_bytes())
    # Derive the only publishable class names from the frozen source plan.
    assert plan['kind']=='dsha-unit-plan' and plan['mode']=='full'
    allowed={cls for row in plan['sources'] for cls in row['classes']}
    methods={(t['classname'],t['method']) for row in plan['sources'] for t in row['tests']}
    assert allowed and methods
    assert reports.resolve()==reports
    digest=hashlib.sha256(); counts=dict(files=0,cases=0,failed=0,skipped=0,unreadable=0)
    failing={}; named=set()
    paths=sorted(reports.glob('TEST-*.xml')) if reports.is_dir() else []
    assert len(paths)<=4096
    for file in paths:
        counts['files']+=1
        assert file.resolve()==file and file.is_file() and file.stat().st_size<=16*2**20
        raw=file.read_bytes(); digest.update(hashlib.sha256(raw).digest())
        try:
            assert not re.search(rb'<!DOCTYPE|<!ENTITY',raw,re.I)
            root=ET.fromstring(raw)
        except (ET.ParseError,AssertionError):
            counts['unreadable']+=1
            continue
        for case in root.iter('testcase'):
            counts['cases']+=1
            result=case.find('failure')
            if result is None:result=case.find('error')
            if result is not None:
                counts['failed']+=1
                cls=case.get('classname','')
                kind=result.get('type','')
                category=('TIMEOUT' if 'Timeout' in kind else 'ASSERTION' if 'Assertion' in kind
                          else 'INITIALIZATION' if case.get('name')=='initializationError' else 'OTHER')
                if cls in allowed and re.fullmatch(r'[A-Za-z0-9_.$]{1,140}',cls):
                    failing[cls,category]=failing.get((cls,category),0)+1
                    method=case.get('name','')
                    if (cls,method) in methods and re.fullmatch(r'[A-Za-z0-9_$]{1,90}',method):
                        named.add((cls,method,category))
            elif case.find('skipped') is not None:counts['skipped']+=1
    print('B368_JVM_XML_COUNTS='+':'.join(str(n) for n in counts.values()))
    print('B368_JVM_XML_CONTENT_SHA256='+digest.hexdigest())
    for (cls,category),count in sorted(failing.items())[:16]:
        print(f'B368_JVM_FAILED_CLASS={cls}:{category}:{count}')
    for cls,method,category in sorted(named)[:16]:
        print(f'B368_JVM_FAILED_METHOD={cls}:{method}:{category}')
    state=p/'unit-state/tasks'
    names=[f.name for f in state.iterdir()] if state.is_dir() else []
    print(f'B368_JVM_HOOK_RECORDS={sum(n.endswith(".start.json") for n in names)}:{sum(n.endswith(".end.json") for n in names)}')
    log=p/'compile.log'; assert log.stat().st_size<=32*2**20
    text=log.read_text(encoding='utf8',errors='replace')
    for label,pattern in {
        'TEST_ASSERTIONS_REPORTED':r'There were failing tests|\d+ tests? completed, \d+ failed',
        'WORKER_ABNORMAL_EXIT':r"Gradle Test Executor [0-9]+'.*non-zero exit|Could not complete execution for Gradle Test Executor|Test worker process.*exited",
        'TEST_TIMEOUT_EXCEPTION':r'(?:TestTimedOutException|java\.util\.concurrent\.TimeoutException)',
        'CONTRACT_REFUSED':r'(?m)^\s*(?:DSHA_[A-Z_]+|UNIT_CONTRACT_[A-Z_]+|UNIT_TEST_CONTRACT_FAILED)(?=[:\r\n]|$)',
        'OFFLINE_CACHE_MISS':r'available for offline mode',
    }.items():
        if re.search(pattern,text,re.I):print('B368_JVM_TASK_DIAG='+label)
    contract=ast.parse((repo/'tools/unit_test_contract.py').read_bytes())
    codes={n.args[0].value for n in ast.walk(contract) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name)
           and n.func.id=='fail' and n.args and isinstance(n.args[0],ast.Constant) and isinstance(n.args[0].value,str)
           and re.fullmatch(r'[A-Z][A-Z_]{2,64}',n.args[0].value)}
    observed=set(re.findall(r'(?m)^\s*(?:> )?([A-Z][A-Z_]{2,64})(?=[:\r\n]|$)',text))
    for code in sorted(codes & observed)[:12]:print('B368_JVM_HOOK_CODE='+code)


if __name__=='__main__':
    assert __debug__ and len(sys.argv)==2
    summarize(Path(sys.argv[1]))
