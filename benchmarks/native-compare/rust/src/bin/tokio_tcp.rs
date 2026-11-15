//! TCP 64 B echo on tokio's default multi-thread runtime: client and server tasks share the runtime and the
//! process; the measured task awaits each echo before sending the next request.
#[path = "../common.rs"]
mod common;
use common::*;
use std::time::Instant;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[tokio::main]
async fn main() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (mut peer, _) = listener.accept().await.unwrap();
            tokio::spawn(async move {
                let mut buf = [0u8; 65536];
                loop {
                    match peer.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => {
                            if peer.write_all(&buf[..n]).await.is_err() {
                                break;
                            }
                        }
                    }
                }
            });
        }
    });
    let mut client = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let request = [b'r'; SIZE];
    let mut reply = [0u8; SIZE];
    let mut rec = Recorder::new("rust-tokio", "tcp", 0);
    loop {
        let t = Instant::now();
        client.write_all(&request).await.unwrap();
        client.read_exact(&mut reply).await.unwrap();
        if !rec.record(t.elapsed()) {
            break;
        }
    }
}
