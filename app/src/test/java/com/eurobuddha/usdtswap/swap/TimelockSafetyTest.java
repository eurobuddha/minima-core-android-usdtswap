package com.eurobuddha.usdtswap.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * STEP: timelock ordering — the property that makes the swap ATOMIC rather than a way to lose money.
 *
 * The initiator locks the first leg (long timelock) and knows the secret. The responder locks a counter-leg
 * (short timelock) and does NOT. The initiator claims the counter-leg, revealing the secret; the responder
 * then claims the first leg. For nobody to be able to BOTH refund their own leg AND claim the other's, the
 * counter-leg must always expire strictly before the first leg — with a margin large enough to land a claim
 * on the other chain.
 *
 * usdtSwap encodes this as constants copied from the upstream bridge's htlcvars.js. If an edit ever weakens
 * the ordering (CP ≥ CHECK, or CHECK ≠ half, or the two chains' windows drift apart in wall-clock), these
 * assertions fail before a coin is ever locked. All values referenced are compile-time constants, so this
 * test reads them without loading the Android-coupled {@link SwapEngine}.
 *
 * Wall-clock legend (MINIMA_BLOCK_TIME = 50 s/block):
 *   first leg      : 144 blocks / 7200 s  = 2 h
 *   half-window gate: 72 blocks / 3600 s  = 1 h   (responder only engages if the first leg has ≥ this left)
 *   counter-leg    :  36 blocks / 1800 s  = 30 min
 */
public class TimelockSafetyTest {

    // ---- 1. counter-leg strictly shorter than the first leg (on BOTH chains) ----

    @Test public void counterLegShorterThanFirstLeg() {
        assertTrue("ETH counter-leg must expire before the ETH first leg",
                SwapEngine.CP_SECS < SwapEngine.TIMELOCK_SECS);
        assertTrue("Minima counter-leg must expire before the Minima first leg",
                SwapEngine.CP_BLOCKS < SwapEngine.TIMELOCK_BLOCKS);
    }

    // ---- 2. the responder's own leg expires before the minimum window it demands of the counterparty ----
    // This is the real safety inequality: CP < CHECK ≤ (remaining first-leg window at responder-lock time).
    // It guarantees the counter-leg is always the first to expire, leaving the responder time to claim.

    @Test public void counterLegExpiresBeforeGuaranteedRemainingWindow() {
        assertTrue("ETH: counter-leg (" + SwapEngine.CP_SECS + "s) must be < the half-window gate ("
                        + SwapEngine.CP_SECS_CHECK + "s)",
                SwapEngine.CP_SECS < SwapEngine.CP_SECS_CHECK);
        assertTrue("Minima: counter-leg (" + SwapEngine.CP_BLOCKS + " blk) must be < the half-window gate ("
                        + SwapEngine.CP_BLOCKS_CHECK + " blk)",
                SwapEngine.CP_BLOCKS < SwapEngine.CP_BLOCKS_CHECK);
    }

    @Test public void safetyMarginIsAtLeastThirtyMinutes() {
        // Worst case: the initiator reveals at the last instant before the counter-leg expires. The responder
        // then has (remaining first-leg window − counter-leg) to claim. Prove that margin is comfortably large.
        long ethMarginSecs = SwapEngine.CP_SECS_CHECK - SwapEngine.CP_SECS;               // 3600 - 1800
        long minimaMarginSecs = (long) (SwapEngine.CP_BLOCKS_CHECK - SwapEngine.CP_BLOCKS)
                * MinimaHtlc.MINIMA_BLOCK_TIME;                                            // (72-36)*50
        assertTrue("ETH claim margin ≥ 30 min", ethMarginSecs >= 30 * 60);
        assertTrue("Minima claim margin ≥ 30 min", minimaMarginSecs >= 30 * 60);
    }

    // ---- 3. RAW-VALUE pins on every window ----
    // The constants are all derived by halving one root (CP = T/4, CHECK = T/2), so any inequality between
    // them holds for ANY positive root — the most dangerous edit ("make swaps faster", shrink TIMELOCK_SECS)
    // scales them together and every relative assertion still passes. These absolute pins are the ones with
    // teeth: halving TIMELOCK_SECS makes CP_SECS 900, and `CP_SECS == 1800` fails immediately. Any change to
    // these numbers is a deliberate protocol change that must be re-reviewed against the bridge's htlcvars.js.

    @Test public void firstLegWindowsArePinned() {
        assertEquals("first Minima leg = 144 blocks (2 h)", 144, SwapEngine.TIMELOCK_BLOCKS);
        assertEquals("first ETH leg = 7200 s (2 h)", 7200, SwapEngine.TIMELOCK_SECS);
    }

    @Test public void halfWindowGatesArePinned() {
        assertEquals("Minima engage gate = 72 blocks (1 h)", 72, SwapEngine.CP_BLOCKS_CHECK);
        assertEquals("ETH engage gate = 3600 s (1 h)", 3600, SwapEngine.CP_SECS_CHECK);
    }

    @Test public void counterLegWindowsArePinned() {
        assertEquals("Minima counter-leg = 36 blocks (30 min)", 36, SwapEngine.CP_BLOCKS);
        assertEquals("ETH counter-leg = 1800 s (30 min)", 1800, SwapEngine.CP_SECS);
    }

    // ---- 4. the two chains' windows are the SAME wall-clock time (blocks × block-time == seconds) ----
    // Both legs live on different chains; if a block-window and a seconds-window disagree on real duration,
    // the ordering that is safe in blocks could be unsafe in seconds. Pin them equal.

    @Test public void blockAndSecondWindowsAgreeInWallClock() {
        int bt = MinimaHtlc.MINIMA_BLOCK_TIME;
        assertEquals("first leg: blocks × block-time == secs",
                SwapEngine.TIMELOCK_SECS, (long) SwapEngine.TIMELOCK_BLOCKS * bt);
        assertEquals("counter-leg: blocks × block-time == secs",
                SwapEngine.CP_SECS, (long) SwapEngine.CP_BLOCKS * bt);
        assertEquals("half-window gate: blocks × block-time == secs",
                SwapEngine.CP_SECS_CHECK, (long) SwapEngine.CP_BLOCKS_CHECK * bt);
    }

    // (The old "engine == htlc first-leg block" check was removed: SwapEngine.TIMELOCK_BLOCKS is *defined* as
    //  MinimaHtlc.TIMELOCK_BLOCKS, so that assertion could never fail. The MinimaHtlc↔SwapEngine linkage is
    //  instead exercised meaningfully by blockAndSecondWindowsAgreeInWallClock (via MINIMA_BLOCK_TIME) and by
    //  the raw pins above, which DO fail if either side's number drifts.)
}
