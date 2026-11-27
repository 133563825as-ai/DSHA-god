"""Extract only fixed task names and Git-locked coordinates from Gradle failures."""
import hashlib
import json
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET

LOCK_SHA = 'ecd1db829722c4c414e180e4839670e6fe905fb89d171bc53a1a4018d1a49d26'
TOKEN = r'[A-Za-z0-9_.+\-]+'
GAV = rf'{TOKEN}:{TOKEN}:{TOKEN}'


def locked(lock_bytes):
    assert hashlib.sha256(lock_bytes).hexdigest() == LOCK_SHA
    root = ET.fromstring(lock_bytes)
    return {':'.join(c.attrib[k] for k in ('group', 'name', 'version')):
            {a.attrib['name'] for a in c.findall('{*}artifact') if re.fullmatch(TOKEN, a.attrib['name'])}
            for c in root.findall('./{*}components/{*}component')}


def summarize(text, tasks, allowed):
    failures = set(re.findall(r"(?m)^(?:> Task (:[A-Za-z0-9_:]+) FAILED\s*$|[^\r\n]*?Execution failed for task '(:[A-Za-z0-9_:]+)'\.)", text))
    failed = {a or b for a, b in failures} & set(tasks)
    gavs = set()
    for value in re.findall(rf'(?:Could not (?:resolve|find)|No cached version of) ({GAV})(?=[\s\r\n]|$)', text):
        if value in allowed:
            gavs.add(value)
        elif value.endswith('.') and value[:-1] in allowed:
            gavs.add(value[:-1])
    files = set()
    for name, gav in re.findall(rf'Could not (?:download|find) ({TOKEN}) \(({GAV})\)', text):
        if name in allowed.get(gav, set()):
            files.add((gav, name)); gavs.add(gav)
    return sorted(failed), sorted(gavs)[:12], sorted(files)[:12]


def main():
    assert __debug__ and len(sys.argv) == 5
    phase, log, lock, guard = sys.argv[1:]
    assert phase in ('runtime', 'compile', 'aapt2', 'offline')
    tasks = json.loads(re.search(r'(?m)^def expected = (.*)$', Path(guard).read_text(encoding='utf8'))[1])
    assert len(tasks) == len(set(tasks)) == 31 and all(re.fullmatch(r':app:[A-Za-z0-9_]+', x) for x in tasks)
    assert Path(log).stat().st_size <= 16 * 2**20
    failed, gavs, files = summarize(Path(log).read_text(encoding='utf8', errors='replace'), tasks, locked(Path(lock).read_bytes()))
    for t in failed:
        print(f'B368_COMPILE_FAILED_TASK={phase}{t}')
    for gav in gavs:
        print(f'B368_COMPILE_LOCKED_COORDINATE={phase}:{gav}')
    for gav, name in files:
        print(f'B368_COMPILE_LOCKED_ARTIFACT={phase}:{gav}:{name}')


if __name__ == '__main__':
    main()
