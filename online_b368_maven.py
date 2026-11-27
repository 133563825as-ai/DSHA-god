"""Retrieve exactly six independently locked public Maven originals, with no indexing or execution."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
from xml.etree import ElementTree

C='b2ed231b165c90339a297e136e476e98ade67f11'
CENTRAL='https://repo.maven.apache.org/maven2/'
GOOGLE='https://dl.google.com/dl/android/maven2/'
ROWS=[
    ('groovy',CENTRAL,'org.codehaus.groovy','groovy','3.0.22','jar',None),
    ('httpclient-jar',CENTRAL,'org.apache.httpcomponents','httpclient','4.5.6','jar',None),
    ('httpclient-pom',CENTRAL,'org.apache.httpcomponents','httpclient','4.5.6','pom',None),
    ('annotation-jar',GOOGLE,'androidx.annotation','annotation-jvm','1.9.1','jar',60577),
    ('annotation-module',GOOGLE,'androidx.annotation','annotation-jvm','1.9.1','module',None),
    ('android-json',CENTRAL,'com.vaadin.external.google','android-json','0.0.20131108.vaadin1','jar',18279),
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


def locked(root):
    b=(root/'gradle/verification-metadata.xml').read_bytes()
    assert hashlib.sha256(b).hexdigest()=='ecd1db829722c4c414e180e4839670e6fe905fb89d171bc53a1a4018d1a49d26'
    x=ElementTree.fromstring(b)
    assert x.findtext('./{*}configuration/{*}verify-metadata')=='true'
    d={}
    for a in x.findall('./{*}components/{*}component'):
        k=tuple(a.get(n) for n in ('group','name','version'))
        for r in a.findall('{*}artifact'):
            z=(*k,r.get('name'));assert z not in d
            d[z]={v.get('value') for v in r.findall('{*}sha256')}
    b=(root/'tools/vscreen-test-dependencies.json').read_bytes()
    assert hashlib.sha256(b).hexdigest()=='bd1963560f174870a6daeca33a468a4beb91216e9eebf453e1fac98c0725d9a4'
    j=json.loads(b); p=(root/'tools/test-vscreen-protocol.py').read_bytes()
    out=[]
    for label,base,g,a,v,ext,size in ROWS:
        n=f'{a}-{v}.{ext}';u=base+g.replace('.','/')+f'/{a}/{v}/{n}'
        if label=='android-json':
            assert (j['url'],j['artifact'],j['size'])==(u,n,size);pin=j['sha256']
        else:
            pin,=d[g,a,v,n]
            if label=='annotation-jar':assert ("ANNOTATION_SHA256 = '"+pin+"'").encode() in p and b'ANNOTATION_SIZE = 60577' in p
        assert re.fullmatch(r'[a-f0-9]{64}',pin)
        out.append((label,u,n,size,pin,16*2**20 if ext=='jar' else 512*1024))
    return out


def acquire(opener,folder,row):
    label,url,n,expected,pin,cap=row
    path=folder/(n+'.partial');final=folder/n
    assert not final.exists()
    with opener.open(urllib.request.Request(url,headers={'Accept-Encoding':'identity'}),timeout=25) as r:
        if r.status!=200 or r.url!=url or r.headers.get('Content-Encoding','identity')!='identity':return 'HTTP_SHAPE',0
        h=hashlib.sha256();total=0
        with path.open('xb') as f:
            while chunk:=r.read(1024*1024):
                total+=len(chunk)
                if total>cap:return 'SIZE',total
                h.update(chunk);f.write(chunk)
        if total==0 or expected is not None and total!=expected:return 'SIZE',total
        if h.hexdigest()!=pin:return 'SHA256',total
    os.replace(path,final)
    with final.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==pin
    return 'PASS',total


def main():
    assert __debug__ and len(sys.argv)==3
    p,folder=map(Path,sys.argv[1:]); assert p.resolve()==p and folder.resolve()==folder
    rows=locked(p);folder.mkdir(mode=0o700)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect()); count=0
    for row in rows:
        try:code,n=acquire(opener,folder,row)
        except urllib.error.HTTPError as e:code,n='HTTP_'+str(e.code) if 100<=e.code<=599 else 'HTTP_OTHER',0
        except (urllib.error.URLError, TimeoutError):code,n='TRANSPORT',0
        except OSError:code,n='LOCAL_IO',0
        print('B368_MAVEN_ITEM='+row[0]+':'+code+':'+str(n),flush=True)
        count+=code=='PASS'
    print('B368_MAVEN_PHYSICAL_MATCHES='+str(count))
    return 0 if count==6 else 1


if __name__=='__main__':raise SystemExit(main())
