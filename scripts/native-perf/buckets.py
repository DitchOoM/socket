import pickle,collections,sys,binascii
def dec(n):
    if n.startswith('___') and len(n)>8:
        try: return binascii.unhexlify(n[3:].split('_')[0] if False else n[3:]).decode('latin1')
        except Exception: return n
    return n
IDLE_LEAF={'start_wqthread','__workq_kernreturn','__psynch_cvwait','_pthread_cond_wait','__semwait_signal'}
LOCK_LEAF={'__psynch_mutexwait','__psynch_mutexdrop','__psynch_cvsignal','_pthread_mutex_firstfit_lock_wait','_pthread_mutex_firstfit_lock_slow','_pthread_mutex_firstfit_unlock_slow'}
def side(fr):
    names=[n for n,b in fr]
    j=' '.join(names)
    if 'nw_' in j or 'NwUdp' in j or 'Network' in [b for n,b in fr]: return 'client'
    if 'ServerConnectionUdpChannel' in j or 'PosixUdp' in j or 'ServerEndpoint' in j or 'QuicServer' in j: return 'server'
    return '?'
def cat(tn,fr):
    if not fr: return 'unattributed(no stack)'
    leaf=fr[0][0]
    if 'GC thread' in tn or 'GC Timer' in tn or 'GC finalizer' in tn: return 'Kotlin GC/alloc'
    if leaf in IDLE_LEAF: return 'idle/park (worker waiting)'
    if leaf=='poll' and 'PosixUdp' not in ' '.join(n for n,b in fr[:3]): return 'idle/park (server poll wait)'
    if leaf in LOCK_LEAF: return 'lock/wake handoff (pthread mutex/cv signal)'
    j=' '.join(n for n,b in fr)
    cl='nw_' in j or 'NwUdp' in j
    if leaf=='__sendto': return 'KERNEL send: server POSIX sendto'
    if leaf=='__sendmsg': return 'KERNEL send: client sendmsg (inside NW)'
    if leaf=='__recvmsg': return 'KERNEL recv: client (inside NW)' if 'nw_' in j else 'KERNEL recv: server POSIX recvmsg'
    for n,b in fr:
        n=dec(n)
        if n.startswith(('kotlin::gc','kotlin::alloc','Kotlin_mm','Kotlin_processObject','Kotlin_gc','_xzm','kotlin::mm')) or 'MainGCThread' in n: return 'Kotlin GC/alloc/safepoint'
        if n.startswith('Kotlin_ObjCExport') or n.startswith('-[Kotlin') : return 'K/N ObjC interop (NSObject bridging)'
        if 'quiche' in n or n.startswith(('EVP_','bssl','CRYPTO_','_$LT$quiche','core::','alloc::','std::','_RNv')) : return 'quiche+BoringSSL(+rust rt)'
        if b in ('Network','libdispatch.dylib','libobjc.A.dylib','CFNetwork') or n.startswith(('nw_','__nw','_dispatch','objc_','_os_')): return 'NW client: Network.framework/dispatch/objc'
        if 'com.ditchoom.socket.quic.QuicheDriver' in n or 'ditchoom.socket.quic' in n: return 'Kotlin: QUIC driver/stream'
        if 'com.ditchoom.socket.udp' in n: return 'Kotlin: UDP channel glue'
        if 'kotlinx.coroutines' in n or 'kotlinx.coroutines' in dec(n): return 'Kotlin: coroutines machinery'
    if leaf.startswith(('mach_','__gettimeofday','_tlv')) or fr[0][1] in('libsystem_kernel.dylib','libsystem_pthread.dylib','libsystem_platform.dylib','libdyld.dylib'): return 'other syscall/libsystem'
    return 'other'
if __name__=='__main__':
    rows=pickle.load(open(sys.argv[1],'rb'))
    c=collections.Counter(); s=collections.Counter(); tot=0
    for tn,st,w,fr in rows:
        k=cat(tn,fr); c[k]+=w; tot+=w; s[(k,side(fr))]+=w
    idle=sum(v for k,v in c.items() if k.startswith('idle'))
    busy=tot-idle
    print(f'total {tot/1e9:.1f}s  idle-ish {idle/1e9:.1f}s  busy {busy/1e9:.1f}s (1 sample=1ms)')
    for k,v in c.most_common():
        print(f'{v/1e6:8.0f} ms {100*v/tot:5.1f}% of all  {"" if k.startswith("idle") else format(100*v/busy,"5.1f")+"% of busy"}  {k}')
    if len(sys.argv)>2:
        print('-- by side'); 
        for (k,sd),v in sorted(s.items(),key=lambda x:-x[1])[:25]: print(f'{v/1e6:8.0f} {sd:7s} {k}')
