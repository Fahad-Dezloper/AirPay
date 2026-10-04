import unittest

from duffleserver import activity

ME = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
OTHER = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"


def tx(keys, pre, post, fee=5000, pre_t=None, post_t=None, err=None):
    return {"transaction": {"message": {"accountKeys": keys}},
            "meta": {"err": err, "fee": fee, "preBalances": pre, "postBalances": post,
                     "preTokenBalances": pre_t or [], "postTokenBalances": post_t or []}}


def tok(owner, mint, amount, dec=6):
    return {"owner": owner, "mint": mint, "uiTokenAmount": {"amount": str(amount), "decimals": dec}}


class Describe(unittest.TestCase):
    def test_received_sol(self):
        t = tx([OTHER, ME], [5_000_000_000, 1_000_000], [4_499_995_000, 501_000_000])
        self.assertEqual(activity.describe(t, ME), [{"direction": "in", "amount": "0.5", "symbol": "SOL"}])

    def test_sent_sol_excludes_fee(self):
        t = tx([ME, OTHER], [2_000_000_000, 0], [1_499_995_000, 500_000_000])
        self.assertEqual(activity.describe(t, ME), [{"direction": "out", "amount": "0.5", "symbol": "SOL"}])

    def test_token_send_is_not_a_sol_send(self):
        t = tx([ME, OTHER], [1_000_000_000, 0], [999_995_000, 0],
               pre_t=[tok(ME, USDC, 10_000_000)], post_t=[tok(ME, USDC, 7_500_000)])
        self.assertEqual(activity.describe(t, ME), [{"direction": "out", "amount": "2.5", "symbol": "USDC"}])

    def test_failed_transaction_describes_nothing(self):
        t = tx([ME, OTHER], [1, 1], [0, 2], err={"InstructionError": [0, "x"]})
        self.assertEqual(activity.describe(t, ME), [])

    def test_unrelated_wallet_hears_nothing(self):
        t = tx([OTHER, "11111111111111111111111111111111"], [10, 10], [5, 15])
        self.assertEqual(activity.describe(t, ME), [])

    def test_dust_is_ignored(self):
        t = tx([OTHER, ME], [5_000_000_000, 0], [4_999_994_000, 500])
        self.assertEqual(activity.describe(t, ME), [])


if __name__ == "__main__":
    unittest.main()


class Watcher(unittest.TestCase):
    """The loop a lagging RPC node used to cause: the same payments announced for ever."""

    def setUp(self):
        from duffleserver.service import Service
        from duffleserver.store import Store

        class Rpc:
            def __init__(self): self.lists = []
            def call(self, method, params):
                # A signature starting with "old" happened long before watching began.
                import time
                return [{"signature": s, "err": None, "blockTime": 1 if s.startswith("old") else time.time()}
                        for s in self.lists.pop(0)]
            def transaction(self, sig): return tx([OTHER, ME], [5_000_000_000, 0], [4_499_995_000, 500_000_000])

        self.rpc = Rpc()
        self.svc = Service(Store(), self.rpc)
        self.svc.store.add_device(ME, "t" * 40)
        self.sent = []
        self.svc._deliver = lambda to, ev: self.sent.append(ev["signature"]) or 1

    def look(self, sigs):
        self.rpc.lists.append(sigs)
        return self.svc.watch_wallets()

    def test_the_first_look_announces_nothing(self):
        self.assertEqual(0, self.look(["c", "b", "a"]))

    def test_a_new_payment_is_announced_once(self):
        self.look(["b", "a"])
        self.assertEqual(1, self.look(["c", "b", "a"]))
        self.assertEqual(0, self.look(["c", "b", "a"]))
        self.assertEqual(["c"], self.sent)

    def test_a_node_that_is_behind_does_not_replay_anything(self):
        self.look(["b", "a"])
        self.look(["c", "b", "a"])            # c arrives
        for _ in range(5):
            self.look(["b", "a"])             # a lagging node: c is missing from its answer
            self.look(["c", "b", "a"])        # a caught-up node: c is back
        self.assertEqual(["c"], self.sent)

    def test_an_older_window_from_a_lagging_node_announces_nothing(self):
        self.look(["c", "b", "a"])
        # The node is behind: its window has slid down to a transaction from before the first
        # look that no earlier answer contained.
        self.assertEqual(0, self.look(["b", "a", "old-1"]))
        self.assertEqual(0, self.look(["c", "b", "a"]))
        self.assertEqual([], self.sent)
