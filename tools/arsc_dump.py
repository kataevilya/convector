import struct, sys

data = open(sys.argv[1], 'rb').read()

def u16(o): return struct.unpack_from('<H', data, o)[0]
def u32(o): return struct.unpack_from('<I', data, o)[0]

def parse_pool(off):
    assert u16(off) == 0x0001, hex(u16(off))
    hs, size = u16(off+2), u32(off+4)
    n, sc = u32(off+8), u32(off+12)
    flags = u32(off+16)
    ss, st = u32(off+20), u32(off+24)
    utf8 = flags & 0x100
    strs = []
    for i in range(n):
        rel = u32(off+hs+i*4)
        p = off+ss+rel
        if utf8:
            p += 2 if data[p] & 0x80 else 1
            bl = data[p]; p += 1
            if bl & 0x80:
                bl = ((bl & 0x7f) << 8) | data[p]; p += 1
            strs.append(data[p:p+bl].decode('utf-8', 'replace'))
        else:
            ln = u16(p); p += 2
            if ln & 0x8000:
                ln = ((ln & 0x7fff) << 16) | u16(p); p += 2
            strs.append(data[p:p+ln*2].decode('utf-16-le', 'replace'))
    return strs, off+size, flags

print("table type=0x%04x headerSize=%d size=%d pkgCount=%d filelen=%d" % (u16(0), u16(2), u32(4), u32(8), len(data)))
pool, off, pflags = parse_pool(u16(2))
print("global pool: count=%d utf8=%s size=%d" % (len(pool), bool(pflags & 0x100), len(data)))
print("  contains 'app_name':", 'app_name' in pool)
print("  contains 'ic_launcher':", 'ic_launcher' in pool)
print("  contains 'WebApp':", 'WebApp' in pool)
for s in pool:
    if 'app_name' in s or 'launcher' in s or s == 'WebApp':
        print("   ->", repr(s), "idx", pool.index(s))

while off + 8 <= len(data):
    t, hs, size = u16(off), u16(off+2), u32(off+4)
    if size < 8 or off+size > len(data):
        print("bad chunk at", off, hex(t), size); break
    if t == 0x0200:
        name = data[off+8:off+8+256].decode('utf-16-le').rstrip('\x00')
        tsp, ksp = u32(off+268), u32(off+276)
        tsets, t_end, _ = parse_pool(off+tsp)
        ksets, k_end, _ = parse_pool(off+ksp)
        print("PACKAGE %r id=%d typeStrings=%d keyStrings=%d" % (name, u32(off+8+256), len(tsets), len(ksets)))
        print("  types:", tsets)
        c = off + hs
        pkg_end = off + size
        while c + 8 <= pkg_end:
            ct, chs, cs = u16(c), u16(c+2), u32(c+4)
            if cs < 8 or c+cs > pkg_end: break
            if ct == 0x0201:
                tid, cflags = data[c+8], data[c+9]
                cnt, estart = u32(c+12), u32(c+16)
                cfg = struct.unpack_from('<H', data, c+20+12)[0], struct.unpack_from('<H', data, c+20+14)[0]
                tname = tsets[tid-1] if tid-1 < len(tsets) else '?'
                print("  TYPE %-8s id=%d flags=0x%02x count=%d cfgSize=%d orient=%d density=%d" %
                      (tname, tid, cflags, cnt, struct.unpack_from('<I', data, c+20)[0], cfg[0], cfg[1]))
                if tname in ('mipmap', 'string'):
                    for i in range(cnt):
                        if cflags & 0x02:
                            v = u16(c+chs+i*2)
                            if v == 0xffff: continue
                            eo = v*4
                        elif cflags & 0x01:
                            v = u32(c+chs+i*4); eo = (v >> 16)*4
                        else:
                            v = u32(c+chs+i*4)
                            if v == 0xffffffff: continue
                            eo = v
                        e = c+estart+eo
                        esize, eflags, key = u16(e), u16(e+2), u32(e+4)
                        if eflags & 0x08:
                            print("    COMPACT entry %d" % i); continue
                        if eflags & 0x01:
                            print("    COMPLEX entry %d key=%r" % (i, ksets[key])); continue
                        vo = e+esize
                        dt, dv = data[vo+3], u32(vo+4)
                        kn = ksets[key] if key < len(ksets) else '?'
                        if kn in ('ic_launcher', 'app_name'):
                            print("    key=%-12r esize=%d type=0x%02x data=%#x %s" %
                                  (kn, esize, dt, dv, ('"'+pool[dv]+'"') if dt == 3 and dv < len(pool) else ''))
            c += cs
    off += size
