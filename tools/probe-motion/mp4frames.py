import struct,sys
d=open(sys.argv[1],'rb').read()
def boxes(b,s,e):
    i=s
    while i+8<=e:
        n,t=struct.unpack('>I4s',b[i:i+8])
        if n==1: n=struct.unpack('>Q',b[i+8:i+16])[0]
        yield t,i,n
        if n==0: break
        i+=n
def find(b,s,e,path):
    for t,i,n in boxes(b,s,e):
        if t==path[0]:
            if len(path)==1: yield i,n
            else:
                hdr=8
                yield from find(b,i+hdr,i+n,path[1:])
for i,n in find(d,0,len(d),[b'moov',b'trak',b'mdia',b'minf',b'stbl',b'stsz']):
    cnt=struct.unpack('>I',d[i+16:i+20])[0]; print('frames',cnt)
for i,n in find(d,0,len(d),[b'moov',b'trak',b'mdia',b'mdhd']):
    v=d[i+8]; 
    ts,dur=(struct.unpack('>II',d[i+20:i+28]) if v==0 else (0,0)); print('timescale',ts,'duration s',dur/ts if ts else '?')
