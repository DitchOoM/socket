# Vendored RFCs

The normative texts this library implements, kept in the repository so they can be read, grepped and
cited **offline and at an exact line**.

## Why these are here

The source cites them constantly — `RFC 9000 §9.5`, `RFC 9114 §6.2`, `RFC 9204 §2.1.1` — and until now
every one of those citations was an act of faith at review time. A vendored copy turns "the RFC says
so" into something a reviewer can check in the same terminal:

```bash
grep -n "MUST NOT use a retired" docs/rfc/rfc9000.txt
sed -n '/^5.1.2/,/^5.2/p' docs/rfc/rfc9000.txt        # read a whole section
```

That mattered immediately: #437 turned on whether a peer may terminate a connection because a packet
arrived bearing a connection ID it had just retired. The answer is in §5.2.1 and §5.2.2, and it is not
the one quiche implements.

Vendoring a specification is normally a trap, because specifications move. **RFCs do not** — a
published RFC is immutable by policy; corrections appear as errata or as a new RFC that obsoletes it.
So these files never need to be "kept up to date", only replaced wholesale if we move to a successor
document.

## Which ones, and why these

Exactly the RFCs the codebase actually cites, by citation count at the time of vendoring:

| RFC | Title | Citations |
|---|---|---|
| [9114](rfc9114.txt) | HTTP/3 | 183 |
| [9000](rfc9000.txt) | QUIC: A UDP-Based Multiplexed and Secure Transport | 180 |
| [9204](rfc9204.txt) | QPACK: Field Compression for HTTP/3 | 129 |
| [9221](rfc9221.txt) | An Unreliable Datagram Extension to QUIC | 33 |
| [9220](rfc9220.txt) | Bootstrapping WebSockets with HTTP/3 | 30 |
| [9297](rfc9297.txt) | HTTP Datagrams and the Capsule Protocol | 29 |
| [9443](rfc9443.txt) | Multiplexing Scheme Updates for QUIC | 26 |
| [7541](rfc7541.txt) | HPACK: Header Compression for HTTP/2 | 15 |
| [9002](rfc9002.txt) | QUIC Loss Detection and Congestion Control | 12 |
| [7301](rfc7301.txt) | TLS Application-Layer Protocol Negotiation Extension | 8 |
| [5280](rfc5280.txt) | Internet X.509 PKI Certificate and CRL Profile | 8 |

Cited fewer than eight times and deliberately not vendored: 8489, 8305, 9287, 9113, 7983, 9147, 9110,
and the documentation-only address ranges 5737 / 3849. Fetch one with `./fetch.sh 8489` if a piece of
work starts leaning on it, and move it into the table above in the same change.

## ⚠️ These are the published texts, without errata

An RFC's `.txt` is frozen at publication; approved corrections live separately at
`https://www.rfc-editor.org/errata/rfc<N>`. Before resting an argument on a subtle sentence — an
octet count, a MUST/SHOULD boundary, a field offset — check the errata page. This directory cannot
tell you it is stale, because it never becomes stale; it was simply never complete.

## Provenance and verification

Every file was fetched verbatim from `https://www.rfc-editor.org/rfc/rfc<N>.txt` and is unmodified.
`CHECKSUMS.txt` records a SHA-256 for each.

```bash
./fetch.sh --verify      # check every vendored file against CHECKSUMS.txt
./fetch.sh 9000          # (re-)fetch one RFC and update its checksum
```

## Redistribution

RFCs may be reproduced and distributed in full without modification; these copies retain their IETF
Trust copyright notices exactly as published (BCP 78 and the IETF Trust Legal Provisions). They are
reference material only — nothing here is part of the published library, and they sit outside
`docs/docs/`, which is the Docusaurus content root, so they are not part of the documentation site.
