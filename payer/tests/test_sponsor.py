"""
The fee wallet, attacked. The promise under test: signing a stranger's transaction can cost this
wallet one network fee and nothing else, and only when the fee comes back in USDC.
"""
from __future__ import annotations

import base64
import struct
import tempfile
import os
import unittest

from nacl.signing import SigningKey

from skrpayer import solana as sol
from skrpayer.engine import Refused
from skrpayer.sponsor import COMPUTE_BUDGET, USDC_MINT, Sponsor, check

HASH = sol.b58encode(bytes(range(32)))
SHOP = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
FEE = 10_000


def ix(prog: int, accounts: list[int], data: bytes) -> bytes:
    return bytes([prog]) + sol.compact_u16(len(accounts)) + bytes(accounts) + sol.compact_u16(len(data)) + data


def xfer(amount: int, decimals: int = 6, op: int = 12) -> bytes:
    return bytes([op]) + struct.pack("<QB", amount, decimals)


class Case(unittest.TestCase):
    def setUp(self):
        self.sponsor_key, self.user_key = SigningKey.generate(), SigningKey.generate()
        self.sponsor = sol.b58encode(bytes(self.sponsor_key.verify_key))
        self.user = sol.b58encode(bytes(self.user_key.verify_key))
        self.fee_account = sol.ata(self.sponsor, USDC_MINT, sol.TOKEN_PROGRAM)
        self.src = sol.ata(self.user, USDC_MINT, sol.TOKEN_PROGRAM)
        self.dst = sol.ata(SHOP, USDC_MINT, sol.TOKEN_PROGRAM)
        # sponsor, user | src, dst, fee account | mint, token program, compute budget, system
        self.keys = [self.sponsor, self.user, self.src, self.dst, self.fee_account,
                     USDC_MINT, sol.TOKEN_PROGRAM, COMPUTE_BUDGET, sol.SYSTEM_PROGRAM]

    def good(self) -> list[bytes]:
        return [ix(6, [2, 5, 3, 1], xfer(310_000)), ix(6, [2, 5, 4, 1], xfer(FEE))]

    def tx(self, ixs=None, keys=None, header=(2, 0, 4), signer=None, nsig=2, ours=bytes(64), sign=True) -> bytes:
        keys = keys or self.keys
        ixs = self.good() if ixs is None else ixs
        msg = (bytes(header) + sol.compact_u16(len(keys)) + b"".join(sol.b58decode(k) for k in keys)
               + sol.b58decode(HASH) + sol.compact_u16(len(ixs)) + b"".join(ixs))
        theirs = (signer or self.user_key).sign(msg).signature if sign else bytes(64)
        sigs = (ours + theirs) if nsig == 2 else theirs
        return sol.compact_u16(nsig) + sigs + msg

    def check(self, tx, user=None):
        return check(tx, self.sponsor, self.fee_account, FEE, user or self.user)

    def refused(self, code, tx, user=None):
        with self.assertRaises(Refused) as r:
            self.check(tx, user)
        self.assertEqual(code, r.exception.code)


class TestCheck(Case):
    def test_a_purchase_that_pays_the_fee_is_accepted(self):
        tx = self.tx()
        self.assertEqual(tx[129:], self.check(tx))

    def test_a_payment_reference_may_ride_on_the_transfer(self):
        keys = self.keys + [sol.b58encode(bytes([7]) * 32)]
        ixs = [ix(6, [2, 5, 3, 1, 9], xfer(310_000)), ix(6, [2, 5, 4, 1], xfer(FEE))]
        self.check(self.tx(ixs, keys=keys, header=(2, 0, 5)))

    def test_no_fee_no_signature(self):
        self.refused("fee_missing", self.tx([ix(6, [2, 5, 3, 1], xfer(310_000))]))

    def test_a_fee_that_is_too_small_is_not_a_fee(self):
        self.refused("fee_missing", self.tx([ix(6, [2, 5, 4, 1], xfer(FEE - 1))]))

    def test_a_fee_paid_to_somebody_elses_account_is_not_a_fee(self):
        self.refused("fee_missing", self.tx([ix(6, [2, 5, 3, 1], xfer(FEE))]))

    def test_the_fee_wallet_may_not_be_named_inside_any_instruction(self):
        # As the authority of a transfer: this would spend the fee wallet's own tokens.
        self.refused("touches_sponsor", self.tx([ix(6, [4, 5, 3, 0], xfer(FEE)), self.good()[1]]))
        # As a read-only passenger: harmless-looking, refused all the same.
        self.refused("touches_sponsor", self.tx([ix(6, [2, 5, 4, 1, 0], xfer(FEE))]))

    def test_a_system_transfer_out_of_the_fee_wallet_is_refused(self):
        drain = ix(8, [0, 1], struct.pack("<IQ", 2, 1_000_000))
        self.refused("touches_sponsor", self.tx(self.good() + [drain]))

    def test_any_other_program_is_refused_even_when_it_leaves_the_fee_wallet_alone(self):
        other = ix(8, [1, 3], struct.pack("<IQ", 2, 1))
        self.refused("unexpected_instruction", self.tx(self.good() + [other]))

    def test_only_a_checked_transfer_is_a_transfer(self):
        for op in (3, 4, 6, 7, 9):   # Transfer, Approve, SetAuthority, MintTo, CloseAccount
            self.refused("unexpected_instruction", self.tx([ix(6, [2, 5, 4, 1], xfer(FEE, op=op))]))

    def test_another_mint_is_refused(self):
        keys = list(self.keys)
        keys[5] = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        self.refused("unexpected_instruction", self.tx(keys=keys))

    def test_somebody_else_as_the_authority_is_refused(self):
        self.refused("unexpected_instruction", self.tx([ix(6, [2, 5, 4, 3], xfer(FEE))]))

    def test_the_buyer_must_have_signed_this_exact_message(self):
        self.refused("not_signed", self.tx(sign=False))
        self.refused("not_signed", self.tx(signer=SigningKey.generate()))
        tx = bytearray(self.tx())
        tx[-1] ^= 1   # the fee amount's decimals byte, changed after signing
        self.refused("not_signed", bytes(tx))

    def test_the_buyer_must_be_who_the_session_says(self):
        self.refused("wrong_signers", self.tx(), user=SHOP)

    def test_the_fee_wallet_must_be_the_fee_payer(self):
        keys = [self.user, self.sponsor] + self.keys[2:]
        self.refused("wrong_signers", self.tx(keys=keys))

    def test_a_third_signer_is_refused(self):
        self.refused("wrong_signers", self.tx(header=(3, 0, 4)))
        self.refused("wrong_signers", self.tx(nsig=1))

    def test_a_slot_that_is_already_signed_is_refused(self):
        self.refused("already_signed", self.tx(ours=bytes([1]) * 64))

    def test_a_versioned_message_is_refused(self):
        tx = self.tx()
        self.refused("versioned", tx[:129] + bytes([0x80]) + tx[129:])

    def test_a_tip_is_allowed_up_to_the_cap_and_no_further(self):
        limit = ix(7, [], bytes([2]) + struct.pack("<I", 100_000))
        self.check(self.tx([limit, ix(7, [], bytes([3]) + struct.pack("<Q", 100_000))] + self.good()))
        self.refused("tip_too_high",
                     self.tx([limit, ix(7, [], bytes([3]) + struct.pack("<Q", 10_000_000))] + self.good()))
        # A price with no limit is judged at the most the chain would allow.
        self.refused("tip_too_high", self.tx([ix(7, [], bytes([3]) + struct.pack("<Q", 100_000))] + self.good()))

    def test_bytes_after_the_last_instruction_are_refused(self):
        keys = self.keys
        msg = (bytes((2, 0, 4)) + sol.compact_u16(len(keys)) + b"".join(sol.b58decode(k) for k in keys)
               + sol.b58decode(HASH) + sol.compact_u16(2) + b"".join(self.good()) + b"\x00")
        tx = sol.compact_u16(2) + bytes(64) + self.user_key.sign(msg).signature + msg
        self.refused("malformed", tx)

    def test_rubbish_is_refused_not_crashed_on(self):
        for junk in (b"", b"\x02", bytes(200), bytes([2]) + bytes(128), b"\xff" * 300, bytes(2000)):
            with self.assertRaises(Refused):
                self.check(junk)


class FakeRpc:
    def __init__(self):
        self.sent: list[bytes] = []
        self.error: Exception | None = None
        self.account = {"value": {"lamports": 1}}
        #: Base units of SKR the buyer holds.
        self.skr = 0
        self.skr_asked: list[str] = []

    def token_balance(self, account):
        self.skr_asked.append(account)
        if isinstance(self.skr, Exception):
            raise self.skr
        return self.skr

    def call(self, method, params):
        if method == "getAccountInfo":
            return self.account
        if method == "getBalance":
            return {"value": 50_000_000}
        raise AssertionError(method)

    def blockhash(self):
        return HASH, 1

    def send(self, tx):
        if self.error:
            raise self.error
        self.sent.append(tx)
        return "sig"


class TestSponsor(Case):
    def make(self, **kw):
        self.rpc = FakeRpc()
        d = tempfile.mkdtemp()
        s = Sponsor(os.path.join(d, "s.db"), self.sponsor_key, self.rpc, fee_units=FEE, **kw)
        self.assertEqual(self.fee_account, s.fee_account)
        return s

    def b64(self, tx=None):
        return base64.b64encode(tx or self.tx()).decode()

    def test_it_signs_its_own_slot_and_leaves_the_buyers_alone(self):
        s = self.make()
        tx = self.tx()
        out = s.submit(self.b64(tx), self.user)
        sent = self.rpc.sent[0]
        self.assertEqual(tx[65:], sent[65:])
        self.sponsor_key.verify_key.verify(sent[129:], sent[1:65])
        self.assertEqual(sol.b58encode(sent[1:65]), out["signature"])

    def test_one_wallet_cannot_use_up_the_day(self):
        s = self.make(per_user_day=2)
        s.submit(self.b64(), self.user, now=1000.0)
        s.submit(self.b64(self.tx([ix(6, [2, 5, 4, 1], xfer(FEE + 1))])), self.user, now=1001.0)
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(self.tx([ix(6, [2, 5, 4, 1], xfer(FEE + 2))])), self.user, now=1002.0)
        self.assertEqual("sponsor_limit", r.exception.code)
        # A day later it is allowed again.
        s.submit(self.b64(self.tx([ix(6, [2, 5, 4, 1], xfer(FEE + 3))])), self.user, now=1002.0 + 86_401)

    def test_a_transaction_the_node_refuses_is_reported_and_not_counted(self):
        s = self.make(per_user_day=1)
        self.rpc.error = sol.RpcError("Transaction simulation failed")
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(), self.user)
        self.assertEqual("rejected", r.exception.code)
        self.rpc.error = None
        s.submit(self.b64(), self.user)

    def test_nothing_is_signed_until_the_fee_has_somewhere_to_land(self):
        s = self.make()
        self.rpc.account = {"value": None}
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(), self.user)
        self.assertEqual("sponsor_not_ready", r.exception.code)
        # It made its own token account instead, signed by itself and nobody else.
        self.assertEqual(1, len(self.rpc.sent))
        self.assertEqual(1, self.rpc.sent[0][0])
        self.assertFalse(s.info()["enabled"])

    def test_bad_input_is_refused(self):
        s = self.make()
        for tx, user in (("not base64!!", self.user), (None, self.user), (self.b64(), "nope")):
            with self.assertRaises(Refused):
                s.submit(tx, user)
        self.assertEqual([], self.rpc.sent)


if __name__ == "__main__":
    unittest.main()


class TestHolder(TestSponsor):
    """A wallet holding enough SKR pays no fee. Nothing else about what is signed changes."""

    def no_fee(self):
        return self.tx([ix(6, [2, 5, 3, 1], xfer(310_000))])

    def test_a_holder_pays_no_fee(self):
        s = self.make()
        self.rpc.skr = 1_000 * 10 ** 6
        out = s.submit(self.b64(self.no_fee()), self.user)
        self.assertEqual(1, len(self.rpc.sent))
        self.assertTrue(out["signature"])
        # It was the buyer's own SKR account that was read, not one the phone named.
        self.assertEqual([sol.ata(self.user, "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", sol.TOKEN_PROGRAM)],
                         self.rpc.skr_asked)

    def test_one_unit_short_is_not_a_holder(self):
        s = self.make()
        self.rpc.skr = 1_000 * 10 ** 6 - 1
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(self.no_fee()), self.user)
        self.assertEqual("fee_missing", r.exception.code)
        self.assertEqual([], self.rpc.sent)

    def test_a_node_that_cannot_say_means_the_fee_is_owed(self):
        s = self.make()
        self.rpc.skr = sol.RpcError("down")
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(self.no_fee()), self.user)
        self.assertEqual("fee_missing", r.exception.code)

    def test_holding_skr_does_not_widen_what_is_signed(self):
        s = self.make()
        self.rpc.skr = 5_000 * 10 ** 6
        # Naming the fee wallet in an instruction, or calling any other program: still refused.
        for bad, code in (
            (self.tx([ix(6, [2, 5, 3, 1], xfer(310_000)), ix(8, [0, 1], struct.pack("<IQ", 2, 1_000))]), "touches_sponsor"),
            (self.tx([ix(7, [1, 3], struct.pack("<IQ", 2, 1_000))]), "unexpected_instruction"),
            (self.tx([ix(6, [2, 5, 3, 0], xfer(310_000))]), "touches_sponsor"),
        ):
            with self.assertRaises(Refused) as r:
                s.submit(self.b64(bad), self.user)
            self.assertEqual(code, r.exception.code)
        self.assertEqual([], self.rpc.sent)

    def sol(self, lamports=1_000_000, accounts=(1, 3), op=2):
        return ix(8, list(accounts), struct.pack("<IQ", op, lamports))

    def test_a_holder_may_send_sol_and_other_tokens(self):
        s = self.make()
        self.rpc.skr = 1_000 * 10 ** 6
        # SOL to somebody, with a reference riding along; and a token that is not USDC.
        s.submit(self.b64(self.tx([self.sol(accounts=(1, 3, 4))])), self.user, now=1.0)
        other = self.keys[:5] + [SHOP] + self.keys[6:]
        s.submit(self.b64(self.tx([ix(6, [2, 5, 3, 1], xfer(5, decimals=9))], keys=other)), self.user, now=2.0)
        self.assertEqual(2, len(self.rpc.sent))

    def test_somebody_who_does_not_hold_skr_may_not(self):
        s = self.make()
        for bad in (self.tx([self.sol(), ix(6, [2, 5, 4, 1], xfer(FEE))]),
                    self.tx([ix(6, [2, 5, 3, 1], xfer(5, decimals=9)), ix(6, [2, 5, 4, 1], xfer(FEE))])):
            with self.assertRaises(Refused) as r:
                s.submit(self.b64(bad), self.user)
            self.assertEqual("unexpected_instruction", r.exception.code)
        self.assertEqual([], self.rpc.sent)

    def test_a_holders_sol_transfer_can_only_be_a_transfer_out_of_their_own_wallet(self):
        s = self.make()
        self.rpc.skr = 1_000 * 10 ** 6
        for bad, code in (
            (self.tx([self.sol(accounts=(0, 3))]), "touches_sponsor"),        # out of the fee wallet
            (self.tx([self.sol(accounts=(1, 0))]), "touches_sponsor"),        # naming the fee wallet at all
            (self.tx([self.sol(accounts=(3, 1))]), "unexpected_instruction"),  # out of somebody else's
            (self.tx([self.sol(op=0)]), "unexpected_instruction"),            # create account
            (self.tx([self.sol(op=1)]), "unexpected_instruction"),            # assign
            (self.tx([self.sol(lamports=0)]), "unexpected_instruction"),
            (self.tx([ix(8, [1, 3], struct.pack("<I", 2))]), "unexpected_instruction"),  # short data
        ):
            with self.assertRaises(Refused) as r:
                s.submit(self.b64(bad), self.user)
            self.assertEqual(code, r.exception.code)
        self.assertEqual([], self.rpc.sent)

    def test_a_holder_is_still_counted(self):
        s = self.make(per_user_day=1)
        self.rpc.skr = 1_000 * 10 ** 6
        s.submit(self.b64(self.no_fee()), self.user, now=1000.0)
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(self.tx([ix(6, [2, 5, 3, 1], xfer(310_001))])), self.user, now=1001.0)
        self.assertEqual("sponsor_limit", r.exception.code)

    def test_info_says_free_only_to_a_holder(self):
        s = self.make()
        self.assertEqual((False, FEE), (s.info(self.user)["free"], s.info(self.user)["fee_units"]))
        self.rpc.skr = 1_000 * 10 ** 6
        self.assertEqual((True, 0), (s.info(self.user)["free"], s.info(self.user)["fee_units"]))
        self.assertFalse(s.info()["free"])

    def test_a_holder_is_told_so_even_while_the_fee_wallet_cannot_pay(self):
        s = self.make()
        self.rpc.account = {"value": None}
        self.rpc.skr = 1_000 * 10 ** 6
        told = s.info(self.user)
        self.assertEqual((True, False), (told["free"], told["enabled"]))
        # Being told is all it is: nothing is signed until the fee wallet is ready.
        with self.assertRaises(Refused) as r:
            s.submit(self.b64(self.no_fee()), self.user)
        self.assertEqual("sponsor_not_ready", r.exception.code)

    def test_zero_turns_the_waiver_off(self):
        s = self.make(free_at=0)
        self.rpc.skr = 10 ** 12
        with self.assertRaises(Refused):
            s.submit(self.b64(self.no_fee()), self.user)
