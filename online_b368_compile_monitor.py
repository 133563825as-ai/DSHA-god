"""Supervise the actual offline compile in the unprivileged network namespace."""
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess as sp
import sys
import time


def sample():
    kib,=re.findall(r'(?m)^MemAvailable:\s+(\d+) kB$',Path('/proc/meminfo').read_text())
    cap=int(kib)*1024
    group,=[x[3:] for x in Path('/proc/self/cgroup').read_text().splitlines() if x.startswith('0::')]
    root=Path('/sys/fs/cgroup'); leaf=(root/group.lstrip('/')).resolve(strict=True)
    assert leaf==root or leaf.is_relative_to(root)
    assert leaf==root or (leaf/'memory.max').is_file()
    for p in (leaf,*[q for q in leaf.parents if q==root or q.is_relative_to(root)]):
        limit=p/'memory.max'
        if limit.is_file() and (raw:=limit.read_text().strip())!='max':cap=min(cap,max(0,int(raw)-int((p/'memory.current').read_text())))
    return cap//2**20


def main():
    assert __debug__ and os.getuid()!=0 and socket.if_nameindex()==[(1,'lo')]
    status=Path('/proc/self/status').read_text(); assert 'CapEff:\t0000000000000000\n' in status and 'NoNewPrivs:\t1\n' in status
    p=Path(sys.argv[1]); assert p.resolve()==p
    before=sample();time.sleep(3);before=min(before,sample())
    print(f'B368_COMPILE_ENTRY_MIB={before}',flush=True)
    if before<7168 or shutil.disk_usage(p).free<=30*2**30:return 75
    lowest=before
    with (p/'compile.log').open('xb') as f:
        proc=sp.Popen(sys.argv[2:],stdin=sp.DEVNULL,stdout=f,stderr=sp.STDOUT,start_new_session=True)
        try:
            deadline=time.monotonic()+1000
            while proc.poll() is None:
                lowest=min(lowest,sample())
                if lowest<2048 or shutil.disk_usage(p).free<=30*2**30 or time.monotonic()>=deadline:
                    print('B368_COMPILE_RESOURCE_OR_TIME_STOP=true',flush=True);return 75
                time.sleep(.5)
            return proc.returncode
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid,signal.SIGTERM)
                try:proc.wait(timeout=8)
                except sp.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait(timeout=5)
            print(f'B368_COMPILE_MIN_MIB={lowest}',flush=True)


if __name__=='__main__':raise SystemExit(main())
