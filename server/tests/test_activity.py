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
