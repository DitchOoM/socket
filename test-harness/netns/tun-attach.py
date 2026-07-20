#!/usr/bin/env python3
"""Attach a process to a tun device so the kernel reports carrier (LOWER_UP) on it.

A tun/tap created with `ip tuntap add` but with no process holding its fd open stays
NO-CARRIER / operstate=down. That is fine for the native LinuxNetworkMonitor (it keys
"up" on IFF_UP and reads /sys/class/net/<iface>/tun_flags for the Vpn kind), but Java's
NetworkInterface.isUp() also requires IFF_RUNNING, so a carrier-less tun reads as down
and the desktop-JVM route-aware primary derivation skips it. A real VPN always has its
daemon attached to the tun, which is exactly the carrier this restores.

Opens /dev/net/tun, issues TUNSETIFF for the named device (creating it if absent, or
attaching to an existing persistent one), then blocks until signalled. The netns harness
backgrounds this and kills it (SIGTERM) when the scenario finishes.

Usage: tun-attach.py [ifname]   (default: tun0)
"""
import fcntl
import os
import signal
import struct
import sys

TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000

name = (sys.argv[1] if len(sys.argv) > 1 else "tun0").encode()
fd = os.open("/dev/net/tun", os.O_RDWR)
# struct ifreq: char ifr_name[16]; short ifr_flags; — the kernel reads only these leading fields.
fcntl.ioctl(fd, TUNSETIFF, struct.pack("16sH", name, IFF_TUN | IFF_NO_PI))
sys.stderr.write(f"tun-attach: holding {name.decode()} open (carrier up)\n")
sys.stderr.flush()
signal.pause()  # hold the fd open (keeps the tun's carrier up) until the harness kills us
