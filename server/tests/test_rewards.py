"""
The reward rules, one test per rule, each with a case that must earn and a case that must not.

The farming tests matter most: every rule here exists because a Seeker owner could otherwise
pay themselves. Rewards are worth real money, and this audience is made of people who showed
up for airdrops — whatever is left loose will be found.
"""
from __future__ import annotations

import unittest

from duffleserver.rewards import (
    BELOW_MINIMUM,
    MONTHLY_CAP,
    POOL_EMPTY,
    CAMPAIGN_CAP,
    COUNTERPARTY_COOLDOWN,
    FIRST_PAYMENT,
    NEW_COUNTERPARTY,
    NOT_A_SEEKER,
    NOT_A_TAP,
    PER_PAYMENT,
    SELF_PAYMENT,
    STREAK,
    UNKNOWN_TOKEN,
    WEEKLY_CAP,
    Config,
    History,
    Payment,
    evaluate,
    week_of,
)

NOW = 1_800_000_000.0
DAY = 86_400.0
USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
SEEKER = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
FRIEND = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
CFG = Config(lock_days=7, min_payout=100)


def payment(**kw) -> Payment:
    return Payment(**{"reference": "ref", "payer": SEEKER, "counterparty": FRIEND,
                      "token": USDC, "units": 5_000_000, "origin": "tap", **kw})


def history(**kw) -> History:
    """A payer who should earn: a verified Seeker paying an established wallet, first payment."""
    return History(**{"payer_is_seeker": True, "counterparty_first_seen": NOW - 90 * DAY, **kw})


class EarnsTest(unittest.TestCase):
    def test_a_first_tap_earns_the_bonus_and_the_payment(self):
        d = evaluate(payment(), history(), NOW, CFG)
        self.assertEqual([(FIRST_PAYMENT, 250), (PER_PAYMENT, 50)], [(a.kind, a.skr) for a in d.awards])
        self.assertEqual(300, d.total)
        self.assertIsNone(d.refused)

    def test_later_taps_earn_the_flat_amount(self):
        d = evaluate(payment(), history(had_first_payment=True), NOW, CFG)
        self.assertEqual([(PER_PAYMENT, 50)], [(a.kind, a.skr) for a in d.awards])

    def test_the_fourth_payment_of_a_week_completes_the_streak(self):
        d = evaluate(payment(), history(had_first_payment=True, rewarded_this_week=3), NOW, CFG)
        self.assertEqual([(PER_PAYMENT, 50), (STREAK, 200)], [(a.kind, a.skr) for a in d.awards])
        self.assertEqual(250, d.total)

    def test_rewards_are_locked_before_they_settle(self):
        d = evaluate(payment(), history(), NOW, CFG)
        self.assertEqual(NOW + 7 * DAY, d.unlock_at)

    def test_sol_has_its_own_floor(self):
        self.assertTrue(evaluate(payment(token="SOL", units=50_000_000), history(), NOW, CFG).earned)
        self.assertEqual(BELOW_MINIMUM,
                         evaluate(payment(token="SOL", units=49_999_999), history(), NOW, CFG).refused)


class RefusesTest(unittest.TestCase):
    def test_only_a_verified_seeker_earns(self):
        self.assertEqual(NOT_A_SEEKER, evaluate(payment(), history(payer_is_seeker=False), NOW, CFG).refused)

    def test_only_payments_made_by_tapping_earn(self):
        self.assertEqual(NOT_A_TAP, evaluate(payment(origin="request"), history(), NOW, CFG).refused)

    def test_dust_earns_nothing(self):
        self.assertEqual(BELOW_MINIMUM, evaluate(payment(units=4_999_999), history(), NOW, CFG).refused)

    def test_a_token_with_no_known_floor_earns_nothing(self):
        # Otherwise 5,000,000 units of a worthless token would clear a $5 floor it never met.
        self.assertEqual(UNKNOWN_TOKEN, evaluate(payment(token="So11111111111111111111111111111111111111112"),
                                                 history(), NOW, CFG).refused)

    def test_the_weekly_cap_holds(self):
        self.assertEqual(WEEKLY_CAP, evaluate(payment(), history(had_first_payment=True, rewarded_this_week=4),
                                              NOW, CFG).refused)

    def test_the_campaign_cap_holds(self):
        self.assertEqual(CAMPAIGN_CAP, evaluate(payment(), history(had_first_payment=True, earned_in_campaign=2_500),
                                                NOW, CFG).refused)

    def test_the_last_reward_is_trimmed_to_what_is_left(self):
        d = evaluate(payment(), history(had_first_payment=True, earned_in_campaign=2_480), NOW, CFG)
        self.assertEqual(20, d.total)

    def test_a_first_payment_bonus_is_trimmed_by_the_cap_too(self):
        d = evaluate(payment(), history(earned_in_campaign=2_400), NOW, CFG)
        self.assertEqual(100, d.total)
        self.assertEqual([FIRST_PAYMENT], [a.kind for a in d.awards])

    def test_the_monthly_cap_spreads_the_budget_across_people(self):
        self.assertEqual(MONTHLY_CAP, evaluate(payment(), history(had_first_payment=True, earned_this_month=500),
                                               NOW, CFG).refused)
        trimmed = evaluate(payment(), history(had_first_payment=True, earned_this_month=470), NOW, CFG)
        self.assertEqual(30, trimmed.total)

    def test_a_full_streak_week_fits_inside_the_monthly_cap(self):
        # Otherwise the cap silently eats the streak, and nobody can ever complete one.
        full_week = CFG.weekly_payments * CFG.per_payment + CFG.streak
        self.assertLessEqual(full_week, CFG.monthly_cap)
        self.assertLessEqual(CFG.first_payment + CFG.per_payment, CFG.monthly_cap)

    def test_nothing_is_paid_from_an_empty_pool(self):
        self.assertEqual(POOL_EMPTY, evaluate(payment(), history(had_first_payment=True, pool_left=0), NOW, CFG).refused)

    def test_the_pool_trims_the_last_reward_of_the_week(self):
        d = evaluate(payment(), history(had_first_payment=True, pool_left=20), NOW, CFG)
        self.assertEqual(20, d.total)

    def test_no_pool_set_means_no_pool_limit(self):
        self.assertTrue(evaluate(payment(), history(had_first_payment=True, pool_left=None), NOW, CFG).earned)


class FarmingTest(unittest.TestCase):
    """Each of these is a way to pay yourself. None of them may earn."""

    def test_paying_your_own_wallet_earns_nothing(self):
        self.assertEqual(SELF_PAYMENT, evaluate(payment(counterparty=SEEKER), history(), NOW, CFG).refused)

    def test_a_fresh_wallet_earns_nothing(self):
        # The spare-phone attack: one Seeker, a second phone, a new wallet every time.
        self.assertEqual(NEW_COUNTERPARTY, evaluate(payment(), history(counterparty_first_seen=None), NOW, CFG).refused)

    def test_a_wallet_made_this_week_earns_nothing(self):
        self.assertEqual(NEW_COUNTERPARTY,
                         evaluate(payment(), history(counterparty_first_seen=NOW - 6 * DAY), NOW, CFG).refused)

    def test_a_wallet_a_week_old_is_accepted(self):
        self.assertTrue(evaluate(payment(), history(counterparty_first_seen=NOW - 7 * DAY), NOW, CFG).earned)

    def test_another_seeker_is_accepted_whatever_their_wallet_age(self):
        # A Seeker costs about $500, which is its own defence.
        self.assertTrue(evaluate(payment(), history(counterparty_is_seeker=True, counterparty_first_seen=None),
                                 NOW, CFG).earned)

    def test_a_chain_we_could_not_ask_is_not_a_seeker(self):
        # counterparty_is_seeker defaults to False: unknown never counts as verified.
        self.assertEqual(NEW_COUNTERPARTY, evaluate(payment(), History(payer_is_seeker=True), NOW, CFG).refused)

    def test_two_friends_paying_each_other_in_circles_earn_once(self):
        first = evaluate(payment(), history(), NOW, CFG)
        self.assertTrue(first.earned)
        again = evaluate(payment(), history(had_first_payment=True, rewarded_this_week=1,
                                            last_reward_with_counterparty=NOW), NOW + 600, CFG)
        self.assertEqual(COUNTERPARTY_COOLDOWN, again.refused)

    def test_the_same_person_earns_again_after_thirty_days(self):
        d = evaluate(payment(), history(had_first_payment=True,
                                        last_reward_with_counterparty=NOW - 31 * DAY), NOW, CFG)
        self.assertTrue(d.earned)

    def test_a_farmer_at_full_tilt_cannot_exceed_the_campaign_cap(self):
        # Four new, established counterparties a week, every week, first payment included.
        # Months roll over, so only the campaign cap and the pool are left to stop them.
        earned = 0
        for i in range(200):
            h = history(had_first_payment=i > 0, rewarded_this_week=i % 4, earned_in_campaign=earned,
                        earned_this_month=0)
            earned += evaluate(payment(counterparty=f"cp{i}"), h, NOW + (i // 4) * 7 * DAY, CFG).total
        self.assertLessEqual(earned, CFG.user_cap)
        self.assertEqual(CFG.user_cap, earned, "a determined user should be able to reach the cap")


class WeekTest(unittest.TestCase):
    def test_a_week_is_seven_days_and_both_rules_agree_on_it(self):
        start = week_of(NOW) * 7 * DAY  # anchor to a week boundary, not an arbitrary moment
        self.assertEqual(week_of(start), week_of(start + 6 * DAY))
        self.assertEqual(week_of(start) + 1, week_of(start + 7 * DAY))


class BudgetTest(unittest.TestCase):
    """The campaign arithmetic from REWARDS_PLAN.md, so a change to the numbers shows up here."""

    def test_the_pool_covers_the_users_we_planned_for(self):
        pool = 1_000_000
        self.assertEqual(400, pool // CFG.user_cap)  # users at the full cap

    def test_a_month_of_rewards_beats_what_a_typical_holder_earns_staking(self):
        # A 5,000 SKR holder (the smallest Season 1 tier) earns 100 SKR a month at 24% APY.
        self.assertGreater(CFG.monthly_cap, 5_000 * 0.24 / 12)

    def test_the_monthly_budget_reaches_enough_people(self):
        monthly_budget = CFG.weekly_pool * 52 / 12  # about 10,400 SKR at $200
        self.assertGreaterEqual(monthly_budget / CFG.monthly_cap, 20)


class ConfigTest(unittest.TestCase):
    """The amounts have to be changeable while it runs, without a rebuild or a restart."""

    def setUp(self):
        from duffleserver.service import Service
        from duffleserver.store import Store
        from tests.test_server import FakeRpc
        self.svc = Service(Store(), FakeRpc())

    def test_defaults_until_told_otherwise(self):
        self.assertEqual(Config().per_payment, self.svc.reward_config().per_payment)

    def test_a_change_takes_effect_on_the_next_payment(self):
        self.svc.set_reward_config({"per_payment": 75})
        d = evaluate(payment(), history(had_first_payment=True), NOW, self.svc.reward_config())
        self.assertEqual(75, d.total)

    def test_unknown_settings_are_ignored_not_invented(self):
        out = self.svc.set_reward_config({"per_payment": 60, "pre_payment": 999, "nonsense": 1})
        self.assertEqual({"per_payment": 60}, out["changed"])
        self.assertEqual(["nonsense", "pre_payment"], out["ignored"])

    def test_a_nonsense_value_is_dropped_rather_than_breaking_rewards(self):
        self.svc.store.set_reward_config({"per_payment": "fifty"})
        self.assertEqual(Config().per_payment, self.svc.reward_config().per_payment)

    def test_rewards_can_be_switched_off_by_emptying_the_pool(self):
        self.svc.set_reward_config({"weekly_pool": 0})
        cfg = self.svc.reward_config()
        d = evaluate(payment(), history(had_first_payment=True, pool_left=cfg.weekly_pool), NOW, cfg)
        self.assertEqual(POOL_EMPTY, d.refused)


if __name__ == "__main__":
    unittest.main()
