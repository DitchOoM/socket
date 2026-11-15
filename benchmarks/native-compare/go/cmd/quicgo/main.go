// quic-go: client and server in one process, goroutine per stream, GOMAXPROCS threads, quic-go's default
// congestion control and pacing. Certificate verification is off (InsecureSkipVerify) like every other program.
package main

import (
	"context"
	"crypto/tls"
	"io"
	"os"
	"time"

	"github.com/quic-go/quic-go"
	"nativecompare/rec"
)

func serve(s *quic.Stream) {
	buf := make([]byte, 65536)
	echo, first := false, true
	for {
		n, err := s.Read(buf)
		if n > 0 {
			if first {
				echo, first = buf[0] == 'E', false
			}
			if echo {
				if _, werr := s.Write(buf[:n]); werr != nil {
					return
				}
			}
		}
		if err != nil {
			break
		}
	}
	s.Close()
}

func main() {
	workload := os.Args[1]
	dir := os.Getenv("BENCH_CERT_DIR")
	cert, err := tls.LoadX509KeyPair(dir+"/cert.crt", dir+"/cert.key")
	if err != nil {
		panic(err)
	}
	conf := &quic.Config{
		MaxIdleTimeout:                 10 * time.Minute,
		MaxIncomingStreams:             1_000_000,
		InitialStreamReceiveWindow:     16 << 20,
		MaxStreamReceiveWindow:         16 << 20,
		InitialConnectionReceiveWindow: 64 << 20,
		MaxConnectionReceiveWindow:     64 << 20,
	}
	ln, err := quic.ListenAddr("127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"bench"}}, conf)
	if err != nil {
		panic(err)
	}
	go func() {
		for {
			c, err := ln.Accept(context.Background())
			if err != nil {
				return
			}
			go func() {
				for {
					s, err := c.AcceptStream(context.Background())
					if err != nil {
						return
					}
					go serve(s)
				}
			}()
		}
	}()
	conn, err := quic.DialAddr(context.Background(), ln.Addr().String(),
		&tls.Config{InsecureSkipVerify: true, NextProtos: []string{"bench"}, ServerName: "localhost"}, conf)
	if err != nil {
		panic(err)
	}
	req := make([]byte, rec.Size)
	req[0] = 'E'
	for i := 1; i < rec.Size; i++ {
		req[i] = 'r'
	}
	chunk := make([]byte, rec.Chunk)
	for i := range chunk {
		chunk[i] = 'D'
	}
	reply := make([]byte, rec.Size)
	var long *quic.Stream
	if workload == "qrr" {
		long, err = conn.OpenStreamSync(context.Background())
		if err != nil {
			panic(err)
		}
	}
	bytes := 0
	if workload == "qbulk" {
		bytes = rec.BulkBytes
	}
	r := rec.New("go-quic-go", workload, bytes)
	for {
		t := time.Now()
		switch workload {
		case "qrr":
			long.Write(req)
			io.ReadFull(long, reply)
		case "qspr":
			s, _ := conn.OpenStreamSync(context.Background())
			s.Write(req)
			s.Close()
			io.Copy(io.Discard, s)
		default:
			s, _ := conn.OpenStreamSync(context.Background())
			for i := 0; i < rec.BulkBytes/rec.Chunk; i++ {
				s.Write(chunk)
			}
			s.Close()
			io.Copy(io.Discard, s)
		}
		if !r.Record(time.Since(t)) {
			return
		}
	}
}
