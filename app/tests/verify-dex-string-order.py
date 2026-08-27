#!/usr/bin/env python3
import sys,zipfile,struct,hashlib,zlib
apk=sys.argv[1]
with zipfile.ZipFile(apk) as z:d=z.read('classes.dex')
assert struct.unpack('<I',d[8:12])[0]==(zlib.adler32(d[12:])&0xffffffff),'bad Adler32'
assert d[12:32]==hashlib.sha1(d[32:]).digest(),'bad SHA1'
sz,off=struct.unpack_from('<II',d,56)
def uleb(data,off):
 r=s=0;p=off
 while True:
  b=data[p];p+=1;r|=(b&127)<<s
  if not b&128:return r,p
  s+=7
vals=[]
for i in range(sz):
 so=struct.unpack_from('<I',d,off+i*4)[0];_,p=uleb(d,so);e=d.find(b'\0',p);vals.append(d[p:e])
for i in range(3,len(vals)):
 if vals[i-1]>=vals[i]:raise SystemExit(f'string_ids order failure at {i-1}/{i}')
print('DEX string_ids/signature/checksum PASS')
