"""Worker-local transport policy for the unchanged Robolectric supplier."""
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import math
from pathlib import Path
import runpy
import sys
import time
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def requested_wait(header, now):
    if header is None:
        return 65
    text=header.strip()
    if text.isascii() and text.isdecimal() and len(text)<=8:
        return int(text)
    try:
        date=parsedate_to_datetime(text)
        if date.tzinfo is None:
            raise ValueError
        return max(0, math.ceil((date-now).total_seconds()))
    except (ValueError,TypeError,OverflowError):
        return None


def policies(allowed, underlying, sleeper=time.sleep,
             now=lambda: datetime.now(timezone.utc), monotonic=time.monotonic):
    deadline=monotonic()+875  # outer watchdog: 900 seconds
    delay=None
    transfer_timeout=None
    def opener(url, timeout):
        nonlocal delay, transfer_timeout
        assert url in allowed
        delay=None
        transfer_timeout=timeout
        try:
            return underlying(url, timeout=timeout)
        except urllib.error.HTTPError as error:
            if error.code==429:
                value=requested_wait(error.headers.get('Retry-After'),now())
                if value is None:
                    # RuntimeError is deliberately not caught by the original retry loop.
                    raise RuntimeError('ROBOLECTRIC_RETRY_AFTER_INVALID') from None
                delay=max(65,value)
            raise
    def wait(default):
        seconds=max(default, delay or 0)
        if seconds + transfer_timeout > deadline-monotonic():
            raise RuntimeError('ROBOLECTRIC_RETRY_WAIT_BUDGET')
        if delay is not None:
            print(f'B368_ROBO_BACKOFF={seconds}',flush=True)
        sleeper(seconds)
    return opener, wait


def main():
    p=Path(sys.argv[1]).resolve(strict=True)
    assert __debug__ and len(sys.argv)==2 and not (p/'robo').exists()
    source=runpy.run_path(str(p/'receiver/tools/prepare-robolectric-sdks.py'))
    lock=source['load_lock']()
    allowed={source['url'](lock,row) for row in lock['jars']}
    assert len(allowed)==5 and all(url.startswith('https://repo1.maven.org/maven2/') for url in allowed)
    transport=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    opener,wait=policies(allowed,transport.open)
    source['prepare'](p/'robo',lock=lock,opener=opener,sleep=wait)


if __name__=='__main__':
    main()
