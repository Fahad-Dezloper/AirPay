"""
Who is calling, proven — not claimed.

Until this existed every endpoint believed the address in the request body, so anyone could
register a push token for somebody else's wallet, send a message as them, or claim a referral
code in their name. With money at stake that is not acceptable, so identity is now a signature.

The shape is Sign-In With Solana, kept deliberately small:

  1. `challenge(address)` — the server invents a random one-time nonce, writes the exact text the
     wallet must sign, and remembers it. The text names the address, the nonce, when it was
     issued and when it dies (5 minutes).
  2. The wallet signs that text (Mobile Wallet Adapter `signMessages`). It costs nothing and
     sends no transaction.
  3. `verify(nonce, signature)` — the server checks the ed25519 signature over the text IT stored,
     never over text the client sent back, so a client cannot talk the server into verifying a
     different message. The nonce is consumed in the same atomic statement that checks it, so a
     captured signature is useless a second time.
  4. A random 256-bit session token comes back. Only its SHA-256 is stored: a stolen database
     cannot be replayed as sessions. Every later call carries it; the server takes the address
     from the token and ignores whatever address the body claims.

Failure is boring on purpose: every refusal is the same `AuthError("denied")` to the caller, so
the response never says whether the nonce was wrong, expired, used or the signature was bad.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import secrets
import time
from datetime import datetime, timezone

from nacl.exceptions import BadSignatureError
from nacl.signing import VerifyKey

NONCE_TTL = 300.0
SESSION_TTL = 30 * 86_400.0
#: A nonce that has been tried this often is burnt, so a signature cannot be brute-forced
#: against one challenge.
MAX_ATTEMPTS = 3
#: Challenges that may be waiting for one address at once. Stops a loop filling the table.
MAX_OPEN_NONCES = 5
#: Sessions kept per address; the oldest is revoked past this.
MAX_SESSIONS = 10
TOKEN_PREFIX = "ap1_"

_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
_B58_INDEX = {c: i for i, c in enumerate(_B58)}


class AuthError(Exception):
    """Always shown to the caller as one undifferentiated refusal."""


class RateLimited(AuthError):
    pass


class WalletInUse(AuthError):
    """The wallet being linked already belongs to a different AirPay account."""


def b58decode(s: str) -> bytes:
    n = 0
    for ch in s:
        try:
            n = n * 58 + _B58_INDEX[ch]
        except KeyError:
            raise ValueError("not base58") from None
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    pad = len(s) - len(s.lstrip("1"))
    return b"\x00" * pad + raw


def b58encode(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    return "1" * (len(b) - len(b.lstrip(b"\x00"))) + out


def _iso(at: float) -> str:
    return datetime.fromtimestamp(at, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class Auth:
    def __init__(self, store, domain: str = "airpay", chain: str = "mainnet"):
        self.store = store
        self.domain = domain
        self.chain = chain

    def message(self, address: str, nonce: str, issued: float, expires: float, purpose: str = "signin") -> str:
        if purpose == "link":
            head = f"{self.domain} wants you to link this Solana account to your AirPay account:\n"
            what = "Link this wallet to your AirPay account. This costs nothing and sends no transaction.\n\n"
        else:
            head = f"{self.domain} wants you to sign in with your Solana account:\n"
            what = "Sign in to AirPay. This costs nothing and sends no transaction.\n\n"
        return (
            head +
            f"{address}\n\n" +
            what +
            f"URI: https://{self.domain}\n"
            "Version: 1\n"
            f"Chain ID: {self.chain}\n"
            f"Nonce: {nonce}\n"
            f"Issued At: {_iso(issued)}\n"
            f"Expiration Time: {_iso(expires)}"
        )

    def challenge(self, address: str, now: float | None = None, purpose: str = "signin") -> dict:
        now = now if now is not None else time.time()
        if purpose not in ("signin", "link"):
            raise AuthError("denied")
        pub = self._pubkey(address)
        if pub is None:
            raise AuthError("denied")
        # At the cap, the OLDEST open challenge is retired to make room, rather than refusing the
        # new one. Refusing locked a wallet out for the full five minutes after a few abandoned
        # prompts — which is exactly what a wallet sheet closing early produces — and gave anyone
        # who knew an address a way to keep its owner from signing in. The table stays bounded
        # (never more than MAX_OPEN_NONCES per address) and the per-IP limit still caps the rate.
        if self.store.open_nonces(address, now) >= MAX_OPEN_NONCES:
            self.store.drop_oldest_nonce(address, now)
        nonce = secrets.token_hex(16)
        expires = now + NONCE_TTL
        text = self.message(address, nonce, now, expires, purpose)
        self.store.put_nonce(nonce, address, text, now, expires, purpose)
        return {"nonce": nonce, "message": text, "expires_at": expires}

    def verify(self, nonce: str, signature_b64: str, now: float | None = None) -> dict:
        now = now if now is not None else time.time()
        if not isinstance(nonce, str) or not isinstance(signature_b64, str) or len(nonce) != 32:
            raise AuthError("denied")
        # One atomic statement checks "exists, unused, unexpired, attempts left" and counts this
        # try. Nothing between the check and the use can be raced.
        row = self.store.try_nonce(nonce, now, MAX_ATTEMPTS)
        # A link challenge signs a different sentence for a different purpose: it must never be
        # accepted as a sign-in, nor the other way round.
        if row is None or row.get("purpose", "signin") != "signin":
            raise AuthError("denied")
        try:
            sig = base64.b64decode(signature_b64, validate=True)
            pub = self._pubkey(row["address"])
            if pub is None or len(sig) != 64:
                raise AuthError("denied")
            VerifyKey(pub).verify(row["message"].encode(), sig)
        except (BadSignatureError, binascii.Error, ValueError):
            raise AuthError("denied") from None
        # Burn it only on success: a wrong signature must not let a stranger use up somebody
        # else's pending challenge — but attempts are capped above, so it cannot be ground down.
        if not self.store.consume_nonce(nonce):
            raise AuthError("denied")
        token = TOKEN_PREFIX + secrets.token_urlsafe(32)
        expires = now + SESSION_TTL
        # The wallet's account, made if this is the first time it is seen. A wallet that has been
        # through the app before — on any phone — comes back to the SAME account, which is what
        # keeps a one-time reward one-time however many phones it is signed in on.
        account, new_account = self.store.ensure_account_ex(row["address"], now)
        self.store.put_session(token_hash(token), row["address"], now, expires, MAX_SESSIONS, account)
        return {"token": token, "address": row["address"], "expires_at": expires,
                "wallets": self.store.family(row["address"]), "new_account": new_account}

    def link(self, token: str | None, nonce: str, signature_b64: str, now: float | None = None) -> dict:
        """Adds a wallet to the signed-in account — proven by THAT wallet signing the link sentence.

        Without the proof, "this wallet is mine too" would be an assertion anybody could make about
        anybody's wallet: attaching a stranger's fresh wallet to your account would lock them out
        of their own sign-up rewards. One signature, once, when a wallet is added; switching
        between wallets afterwards asks for nothing.
        """
        now = now if now is not None else time.time()
        who = self.identity(token, now)
        if who is None or not isinstance(nonce, str) or not isinstance(signature_b64, str) or len(nonce) != 32:
            raise AuthError("denied")
        row = self.store.try_nonce(nonce, now, MAX_ATTEMPTS)
        if row is None or row.get("purpose") != "link":
            raise AuthError("denied")
        try:
            sig = base64.b64decode(signature_b64, validate=True)
            pub = self._pubkey(row["address"])
            if pub is None or len(sig) != 64:
                raise AuthError("denied")
            VerifyKey(pub).verify(row["message"].encode(), sig)
        except (BadSignatureError, binascii.Error, ValueError):
            raise AuthError("denied") from None
        if not self.store.consume_nonce(nonce):
            raise AuthError("denied")
        account = who["account_id"] or self.store.ensure_account(who["address"], now)
        if self.store.link_wallet(account, row["address"], now) != "ok":
            raise WalletInUse("wallet_in_use")
        return {"wallets": self.store.family(who["address"])}

    def identity(self, token: str | None, now: float | None = None) -> dict | None:
        """The wallet that signed in and its account, or None. Never raises."""
        if not isinstance(token, str) or not token.startswith(TOKEN_PREFIX) or len(token) > 200:
            return None
        now = now if now is not None else time.time()
        return self.store.session_info(token_hash(token), now)

    def session(self, token: str | None, now: float | None = None) -> str | None:
        """The address a token belongs to, or None. Never raises."""
        if not isinstance(token, str) or not token.startswith(TOKEN_PREFIX) or len(token) > 200:
            return None
        now = now if now is not None else time.time()
        return self.store.session_address(token_hash(token), now)

    def revoke(self, token: str) -> bool:
        return self.store.revoke_session(token_hash(token), time.time())

    @staticmethod
    def _pubkey(address: str) -> bytes | None:
        if not isinstance(address, str) or not 32 <= len(address) <= 44:
            return None
        try:
            raw = b58decode(address)
        except ValueError:
            return None
        return raw if len(raw) == 32 else None
