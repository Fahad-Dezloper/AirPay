"""
Secret files from environment variables, for hosts that cannot mount files (Railway, Fly.io).

On a host where secrets can only be environment variables, each key file is given as its
contents in base64 under a `*_B64` variable. At start-up this writes each one to a private
file under a temporary folder, points the usual `*_FILE` / path variable at it, and removes
the base64 copy from the environment, so nothing later can log or pass it on. Where the files
are mounted the normal way (docker compose), no `*_B64` variable is set and this does nothing.
"""
from __future__ import annotations

import base64
import os
import tempfile


def materialize(mapping: dict[str, tuple[str, str]], folder: str | None = None) -> list[str]:
    """[mapping]: base64 variable -> (path variable, file name). Returns the path variables set."""
    done = []
    base = None
    for b64_var, (path_var, name) in mapping.items():
        raw = os.environ.pop(b64_var, None)
        if not raw:
            continue
        if base is None:
            base = folder or tempfile.mkdtemp(prefix="secrets-")
            os.chmod(base, 0o700)
        path = os.path.join(base, name)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(base64.b64decode(raw.strip()))
        os.environ[path_var] = path
        done.append(path_var)
    return done


def bind(host: str, port: int, handler, server_cls):
    """An HTTP server on [host], which may be an IPv6 address such as "::" (Railway's private
    network is IPv6); on Linux "::" also accepts IPv4."""
    import socket

    cls = server_cls
    if ":" in host:
        cls = type("V6" + server_cls.__name__, (server_cls,), {"address_family": socket.AF_INET6})
    return cls((host, port), handler)
