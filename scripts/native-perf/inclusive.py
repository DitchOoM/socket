import sys,pickle,collections
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from buckets import cat
S=['nw_connection_send','nw_connection_add_write_request','nw_flow_service_writes','nw_socket_service_writes','nw_socket_finalize_output','__nw_udp_send','nw_udp_send']
R=['nw_connection_receive','add_read_request','nw_flow_service_reads','nw_socket_get_input_frames','nw_socket_update_input_source','nw_flow_input_available','__nw_udp_receive','nw_udp_receive']
SS=['PosixUdpDatagramChannel#send','PosixUdpDatagramChannel.$send','ServerConnectionUdpChannel']
for f in sys.argv[1:]:
    rows=pickle.load(open(f,'rb'))
    tot=sum(r[2] for r in rows); busy=sum(r[2] for r in rows if not cat(r[0],r[3]).startswith('idle'))
    inc=collections.Counter()
    for tn,s,w,fr in rows:
        if cat(tn,fr).startswith('idle'): continue
        j=' '.join(n for n,b in fr)
        if any(x in j for x in S): inc['client NW send path (incl kernel sendmsg)']+=w
        if any(x in j for x in R): inc['client NW recv path (incl kernel recvmsg)']+=w
        if 'PosixUdpDatagramChannel#send' in j or 'PosixUdpDatagramChannel.$send' in j: inc['server POSIX send path (incl sendto)']+=w
        if 'PosixUdpDatagramChannel#receive' in j or 'PosixUdpDatagramChannel.$receive' in j: inc['server POSIX recv path (incl recvmsg)']+=w
        if any(x in j for x in ['quiche_conn_send','quiche_conn_recv']): inc['quiche_conn_send/recv inclusive']+=w
        if 'quiche_conn_send' in j: inc['  quiche_conn_send']+=w
        if 'quiche_conn_recv' in j: inc['  quiche_conn_recv']+=w
    print(f, f'busy={busy/1e9:.1f}s')
    for k,v in sorted(inc.items()): print(f'   {100*v/busy:5.1f}% of busy  {k}')
