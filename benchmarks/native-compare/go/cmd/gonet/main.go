// TCP 64 B echo with Go's net package: one goroutine per connection on the runtime's netpoller (epoll/kqueue),
// GOMAXPROCS threads. The measured goroutine blocks on each echo before sending the next request.
package main

import (
	"io"
	"net"
	"time"

	"nativecompare/rec"
)

func main() {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		panic(err)
	}
	req := make([]byte, rec.Size)
	reply := make([]byte, rec.Size)
	r := rec.New("go-net", "tcp", 0)
	for {
		t := time.Now()
		if _, err := c.Write(req); err != nil {
			panic(err)
		}
		if _, err := io.ReadFull(c, reply); err != nil {
			panic(err)
		}
		if !r.Record(time.Since(t)) {
			return
		}
	}
}
