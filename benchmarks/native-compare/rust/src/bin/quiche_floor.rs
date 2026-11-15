//! The floor: client and server written directly against quiche 0.30.0's Rust API, no framework. Each side is
//! one thread owning one nonblocking UDP socket and one connection, driven by a poll(2) loop. The client's
//! "application" runs inline in its own loop (no hand-off between threads), the server echoes inline.
//! Pacing is enabled in the config but the send-time hint is ignored (quiche's example apps do the same off Linux).
#[path = "../common.rs"]
mod common;
use common::*;
use quiche::{ConnectionId, RecvInfo};
use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Instant;

const MAX_DGRAM: usize = 1350;

/// Same transport settings as benchmarks/jvm's QuicLoopback.OPTIONS and this library's defaults.
fn config(server: bool) -> quiche::Config {
    let mut c = quiche::Config::new(quiche::PROTOCOL_VERSION).unwrap();
    c.set_application_protos(&[b"bench"]).unwrap();
    c.verify_peer(false);
    if server {
        c.load_cert_chain_from_pem_file(&cert("cert.crt")).unwrap();
        c.load_priv_key_from_pem_file(&cert("cert.key")).unwrap();
    }
    c.set_max_idle_timeout(600_000);
    c.set_max_recv_udp_payload_size(MAX_DGRAM);
    c.set_max_send_udp_payload_size(MAX_DGRAM);
    c.set_initial_max_data(64 << 20);
    c.set_initial_max_stream_data_bidi_local(16 << 20);
    c.set_initial_max_stream_data_bidi_remote(16 << 20);
    c.set_initial_max_stream_data_uni(1 << 20);
    c.set_initial_max_streams_bidi(1_000_000);
    c.set_initial_max_streams_uni(100);
    c.set_disable_active_migration(true);
    c.set_active_connection_id_limit(8);
    c.set_cc_algorithm(quiche::CongestionControlAlgorithm::CUBIC);
    c.enable_hystart(true);
    c.enable_pacing(true);
    c.discover_pmtu(false);
    c.grease(true);
    c
}

fn cert(name: &str) -> String {
    let dir = std::env::var("BENCH_CERT_DIR").expect("BENCH_CERT_DIR must name the directory holding cert.crt and cert.key");
    format!("{dir}/{name}")
}

/// One connection plus its socket; `step` is one turn of the event loop.
struct Peer {
    conn: quiche::Connection,
    sock: UdpSocket,
    local: SocketAddr,
    remote: SocketAddr,
    inbuf: Vec<u8>,
    out: Vec<u8>,
}

impl Peer {
    fn flush(&mut self) {
        while let Ok((n, info)) = self.conn.send(&mut self.out) {
            let _ = self.sock.send_to(&self.out[..n], info.to);
        }
    }

    /// Flushes what is queued, waits for a datagram (or the connection's own timer), and feeds it in.
    fn step(&mut self) {
        self.flush();
        let ms = self.conn.timeout().map_or(-1, |d| (d.as_micros() as i64 + 999) / 1000);
        let mut p = libc::pollfd { fd: self.sock.as_raw_fd(), events: libc::POLLIN, revents: 0 };
        let ready = unsafe { libc::poll(&mut p, 1, ms as i32) };
        if ready == 0 {
            self.conn.on_timeout();
            return;
        }
        while let Ok((n, from)) = self.sock.recv_from(&mut self.inbuf) {
            let info = RecvInfo { from, to: self.local };
            let _ = self.conn.recv(&mut self.inbuf[..n], info);
        }
    }
}

fn bind() -> (UdpSocket, SocketAddr) {
    let s = UdpSocket::bind("127.0.0.1:0").unwrap();
    s.set_nonblocking(true).unwrap();
    let a = s.local_addr().unwrap();
    (s, a)
}

fn server(sock: UdpSocket, local: SocketAddr) {
    let mut cfg = config(true);
    let mut inbuf = vec![0u8; 65535];
    let scid_bytes = [7u8; quiche::MAX_CONN_ID_LEN];
    let scid = ConnectionId::from_ref(&scid_bytes);
    let mut peer = loop {
        let mut p = libc::pollfd { fd: sock.as_raw_fd(), events: libc::POLLIN, revents: 0 };
        unsafe { libc::poll(&mut p, 1, -1) };
        if let Ok((n, from)) = sock.recv_from(&mut inbuf) {
            let mut conn = quiche::accept(&scid, None, local, from, &mut cfg).unwrap();
            let _ = conn.recv(&mut inbuf[..n], RecvInfo { from, to: local });
            break Peer { conn, sock, local, remote: from, inbuf, out: vec![0u8; MAX_DGRAM] };
        }
    };
    let _ = peer.remote;
    // stream id -> (echo?). Streams whose first byte is 'E' are echoed; the rest are drained and FIN'd.
    let mut streams: HashMap<u64, bool> = HashMap::new();
    let mut buf = vec![0u8; 65536];
    while !peer.conn.is_closed() {
        peer.step();
        let readable: Vec<u64> = peer.conn.readable().collect();
        for id in readable {
            while let Ok((n, fin)) = peer.conn.stream_recv(id, &mut buf) {
                let echo = *streams.entry(id).or_insert(n > 0 && buf[0] == b'E');
                if echo {
                    let mut off = 0;
                    while off < n || (fin && off == n) {
                        match peer.conn.stream_send(id, &buf[off..n], fin) {
                            Ok(w) => {
                                off += w;
                                if off == n {
                                    break;
                                }
                            }
                            Err(_) => {
                                peer.step();
                            }
                        }
                    }
                } else if fin {
                    let _ = peer.conn.stream_send(id, &[], true);
                }
                if fin {
                    break;
                }
            }
        }
    }
}

fn client_write(peer: &mut Peer, id: u64, mut data: &[u8], fin: bool) {
    loop {
        match peer.conn.stream_send(id, data, fin) {
            Ok(w) if w == data.len() => return,
            Ok(w) => data = &data[w..],
            Err(quiche::Error::Done) => {}
            Err(e) => panic!("stream_send: {e}"),
        }
        peer.step();
    }
}

fn read_exact(peer: &mut Peer, id: u64, want: usize, buf: &mut [u8]) {
    let mut got = 0;
    while got < want {
        match peer.conn.stream_recv(id, buf) {
            Ok((n, _)) => got += n,
            Err(_) => peer.step(),
        }
    }
}

fn read_to_fin(peer: &mut Peer, id: u64, buf: &mut [u8]) {
    loop {
        match peer.conn.stream_recv(id, buf) {
            Ok((_, true)) => return,
            Ok(_) => {}
            Err(_) => peer.step(),
        }
    }
}

fn main() {
    let workload = std::env::args().nth(1).expect("usage: quiche_floor <qrr|qspr|qbulk>");
    let (ssock, saddr) = bind();
    std::thread::spawn(move || server(ssock, saddr));
    let (csock, caddr) = bind();
    let scid_bytes = [9u8; quiche::MAX_CONN_ID_LEN];
    let scid = ConnectionId::from_ref(&scid_bytes);
    let mut cfg = config(false);
    let conn = quiche::connect(Some("localhost"), &scid, caddr, saddr, &mut cfg).unwrap();
    let mut peer = Peer { conn, sock: csock, local: caddr, remote: saddr, inbuf: vec![0u8; 65535], out: vec![0u8; MAX_DGRAM] };
    while !peer.conn.is_established() {
        peer.step();
    }
    let mut buf = vec![0u8; 65536];
    let mut request = vec![b'r'; SIZE];
    request[0] = b'E';
    let mut next_stream = 0u64;
    let bytes = if workload == "qbulk" { BULK_BYTES } else { 0 };
    let mut rec = Recorder::new("quiche-floor", &workload, bytes);
    let chunk = vec![b'D'; CHUNK];
    loop {
        let t = Instant::now();
        match workload.as_str() {
            "qrr" => {
                client_write(&mut peer, 0, &request, false);
                read_exact(&mut peer, 0, SIZE, &mut buf);
            }
            "qspr" => {
                let id = next_stream;
                next_stream += 4;
                client_write(&mut peer, id, &request, true);
                read_to_fin(&mut peer, id, &mut buf);
            }
            _ => {
                let id = next_stream;
                next_stream += 4;
                for _ in 0..BULK_BYTES / CHUNK {
                    client_write(&mut peer, id, &chunk, false);
                }
                client_write(&mut peer, id, &[], true);
                read_to_fin(&mut peer, id, &mut buf);
            }
        }
        if !rec.record(t.elapsed()) {
            break;
        }
    }
}
