//! quinn 0.11 on tokio's default multi-thread runtime (rustls + ring, quinn's default Cubic controller).
//! Client and server endpoints share the runtime; the measured task awaits each echo. The server runs one
//! task per stream. Certificate verification is switched off with a verifier that accepts every certificate.
#[path = "../common.rs"]
mod common;
use common::*;
use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use std::sync::Arc;
use std::time::Instant;

#[derive(Debug)]
struct AcceptAll;
impl ServerCertVerifier for AcceptAll {
    fn verify_server_cert(&self, _: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }
    fn verify_tls13_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

fn transport() -> Arc<quinn::TransportConfig> {
    let mut t = quinn::TransportConfig::default();
    t.receive_window(quinn::VarInt::from_u32(64 << 20));
    t.stream_receive_window(quinn::VarInt::from_u32(16 << 20));
    t.send_window(64 << 20);
    t.max_concurrent_bidi_streams(quinn::VarInt::from_u32(1_000_000));
    t.max_idle_timeout(Some(std::time::Duration::from_secs(600).try_into().unwrap()));
    Arc::new(t)
}

fn pem_chain(path: &str) -> Vec<CertificateDer<'static>> {
    let text = std::fs::read_to_string(path).unwrap();
    text.split("-----END CERTIFICATE-----").filter(|p| p.contains("BEGIN CERTIFICATE")).map(|p| {
        let b64: String = p.split("-----BEGIN CERTIFICATE-----").nth(1).unwrap().lines().collect();
        CertificateDer::from(decode_b64(&b64))
    }).collect()
}

fn decode_b64(s: &str) -> Vec<u8> {
    let table = |c: u8| match c { b'A'..=b'Z' => c - b'A', b'a'..=b'z' => c - b'a' + 26, b'0'..=b'9' => c - b'0' + 52, b'+' => 62, _ => 63 };
    let mut out = Vec::new();
    let (mut acc, mut bits) = (0u32, 0);
    for c in s.bytes().filter(|c| *c != b'=' && !c.is_ascii_whitespace()) {
        acc = (acc << 6) | table(c) as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    out
}

fn private_key(path: &str) -> PrivateKeyDer<'static> {
    let text = std::fs::read_to_string(path).unwrap();
    let b64: String = text.lines().filter(|l| !l.starts_with("-----")).collect();
    let der = decode_b64(&b64);
    if text.contains("BEGIN RSA PRIVATE KEY") {
        PrivateKeyDer::Pkcs1(der.into())
    } else if text.contains("BEGIN EC PRIVATE KEY") {
        PrivateKeyDer::Sec1(der.into())
    } else {
        PrivateKeyDer::Pkcs8(der.into())
    }
}

async fn serve(mut send: quinn::SendStream, mut recv: quinn::RecvStream) {
    let mut buf = vec![0u8; 65536];
    let mut echo: Option<bool> = None;
    while let Ok(Some(n)) = recv.read(&mut buf).await {
        let e = *echo.get_or_insert(buf[0] == b'E');
        if e && send.write_all(&buf[..n]).await.is_err() {
            return;
        }
    }
    let _ = send.finish();
    let _ = send.stopped().await;
}

#[tokio::main]
async fn main() {
    let workload = std::env::args().nth(1).expect("usage: quinn_bench <qrr|qspr|qbulk>");
    let dir = std::env::var("BENCH_CERT_DIR").expect("BENCH_CERT_DIR");
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ServerConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13]).unwrap()
        .with_no_client_auth()
        .with_single_cert(pem_chain(&format!("{dir}/cert.crt")), private_key(&format!("{dir}/cert.key"))).unwrap();
    tls.alpn_protocols = vec![b"bench".to_vec()];
    let mut server_cfg = quinn::ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls).unwrap()));
    server_cfg.transport_config(transport());
    let server = quinn::Endpoint::server(server_cfg, "127.0.0.1:0".parse().unwrap()).unwrap();
    let addr = server.local_addr().unwrap();
    tokio::spawn(async move {
        while let Some(incoming) = server.accept().await {
            tokio::spawn(async move {
                let conn = incoming.await.unwrap();
                while let Ok((send, recv)) = conn.accept_bi().await {
                    tokio::spawn(serve(send, recv));
                }
            });
        }
    });

    let mut ctls = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13]).unwrap()
        .dangerous().with_custom_certificate_verifier(Arc::new(AcceptAll))
        .with_no_client_auth();
    ctls.alpn_protocols = vec![b"bench".to_vec()];
    let mut client_cfg = quinn::ClientConfig::new(Arc::new(QuicClientConfig::try_from(ctls).unwrap()));
    client_cfg.transport_config(transport());
    let mut endpoint = quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).unwrap();
    endpoint.set_default_client_config(client_cfg);
    let conn = endpoint.connect(addr, "localhost").unwrap().await.unwrap();

    let mut request = vec![b'r'; SIZE];
    request[0] = b'E';
    let chunk = vec![b'D'; CHUNK];
    let mut reply = vec![0u8; SIZE];
    let long = if workload == "qrr" { Some(conn.open_bi().await.unwrap()) } else { None };
    let (mut lsend, mut lrecv) = match long { Some((s, r)) => (Some(s), Some(r)), None => (None, None) };
    let bytes = if workload == "qbulk" { BULK_BYTES } else { 0 };
    let mut rec = Recorder::new("rust-quinn", &workload, bytes);
    loop {
        let t = Instant::now();
        match workload.as_str() {
            "qrr" => {
                lsend.as_mut().unwrap().write_all(&request).await.unwrap();
                lrecv.as_mut().unwrap().read_exact(&mut reply).await.unwrap();
            }
            "qspr" => {
                let (mut s, mut r) = conn.open_bi().await.unwrap();
                s.write_all(&request).await.unwrap();
                s.finish().unwrap();
                let _ = r.read_to_end(SIZE).await.unwrap();
            }
            _ => {
                let (mut s, mut r) = conn.open_bi().await.unwrap();
                for _ in 0..BULK_BYTES / CHUNK {
                    s.write_all(&chunk).await.unwrap();
                }
                s.finish().unwrap();
                let _ = r.read_to_end(1).await.unwrap();
            }
        }
        if !rec.record(t.elapsed()) {
            break;
        }
    }
}
