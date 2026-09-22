"""qlog-pair.py pairs lane-named device records with the server's session-named ones by original
destination CID, and names every record it cannot pair."""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("qlog_pair", os.path.join(HERE, "qlog-pair.py"))
qlog_pair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qlog_pair)


def params(initiator, odcid):
    tail = f',"original_destination_connection_id":"{odcid}"' if odcid else ""
    return f'\x1e{{"time":0.0,"name":"quic:parameters_set","data":{{"initiator":"{initiator}","tls_cipher":"AES128_GCM"{tail},"max_idle_timeout":30000}}}}\n'


def write(directory, name, *records):
    with open(os.path.join(directory, name), "w") as f:
        f.write('\x1e{"file_schema":"urn:ietf:params:qlog:file:sequential"}\n' + "".join(records))


class QlogPairTests(unittest.TestCase):
    def test_lane_records_pair_by_original_destination_cid_and_gaps_are_named(self):
        with tempfile.TemporaryDirectory() as server, tempfile.TemporaryDirectory() as device:
            write(server, "quiche-server-aa.sqlog", params("local", "0101"))
            write(server, "quiche-server-aa_seg0002.sqlog", params("local", ""))
            write(server, "quiche-server-bb.sqlog", params("local", "0303"))
            write(device, "conn-v4-0001.sqlog", params("local", ""), params("remote", "0101"))
            write(device, "conn-v4-0001_seg0002.sqlog", params("local", ""))
            write(device, "conn-v6-0001.sqlog", params("local", ""), params("remote", "0202"))
            write(device, "conn-v6-0002.sqlog", params("local", ""))

            rows, unclaimed = qlog_pair.pair(server, [device])

            label = os.path.basename(device)
            self.assertEqual(
                [
                    (f"{label}/conn-v4-0001", "quiche-server-aa", "0101", ""),
                    (f"{label}/conn-v6-0001", "-", "0202", "no server record with this id"),
                    (f"{label}/conn-v6-0002", "-", "-", "no peer parameters: the handshake never completed"),
                ],
                rows,
            )
            self.assertEqual(["quiche-server-bb"], unclaimed)


if __name__ == "__main__":
    unittest.main()
