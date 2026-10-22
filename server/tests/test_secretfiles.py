"""Key files from environment variables (Railway): written privately, and the variable removed."""
import base64
import os
import stat
import tempfile
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from duffleserver.secretfiles import bind, materialize


class SecretFilesTest(unittest.TestCase):
    def test_written_private_and_variable_removed(self):
        os.environ["T_KEY_B64"] = base64.b64encode(b"[1,2,3]").decode()
        os.environ.pop("T_KEYFILE", None)
        folder = tempfile.mkdtemp()
        self.assertEqual(materialize({"T_KEY_B64": ("T_KEYFILE", "k.json")}, folder), ["T_KEYFILE"])
        path = os.environ.pop("T_KEYFILE")
        self.assertEqual(open(path, "rb").read(), b"[1,2,3]")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        self.assertNotIn("T_KEY_B64", os.environ)

    def test_nothing_set_does_nothing(self):
        os.environ.pop("T_NONE_B64", None)
        self.assertEqual(materialize({"T_NONE_B64": ("T_NONE_FILE", "n")}), [])
        self.assertNotIn("T_NONE_FILE", os.environ)

    def test_binds_ipv6_any(self):
        try:
            httpd = bind("::", 0, BaseHTTPRequestHandler, ThreadingHTTPServer)
        except OSError:
            self.skipTest("no IPv6 on this machine")
        self.assertEqual(httpd.address_family.name, "AF_INET6")
        httpd.server_close()


if __name__ == "__main__":
    unittest.main()
