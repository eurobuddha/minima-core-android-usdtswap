package com.eurobuddha.usdtswap.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

/**
 * STEP: amounts. The fund-critical arithmetic that decides how much leaves your wallet on every lock,
 * claim and refund. Two properties dominate the whole app's safety:
 *
 *  1. mxUSDT is a COLOURED Minima token, so a coin's {@code amount} field is the tiny underlying
 *     coloured-Minima (~1e-37 at scale 36) and the REAL value is {@code tokenamount}. Reading the wrong
 *     field once is a silent burn. {@link MinimaHtlc#coinAmount} is the single chokepoint; these tests
 *     pin it to {@code tokenamount} and prove the native-0x00 fallback still works.
 *  2. The Minima leg quantizes lock amounts DOWN to the 6dp trade grain (mxUSDT is 8dp, ERC20 USDT is
 *     6dp, so 6dp is the 1:1 grain). {@link MinimaHtlc#grain} must only ever ROUND DOWN — rounding up
 *     could try to lock more than a coin holds, and the change math must absorb whatever grain drops so
 *     nothing is trapped.
 *
 * These are in the same package as {@link MinimaHtlc} so the fund-relevant package-private helpers
 * ({@code subtract}, {@code positive}, {@code stateAt}) can be asserted directly.
 */
public class AmountGrainTest {

    // ---- grain(): quantize DOWN to 6dp, never up, never trap ----

    @Test public void grainTruncatesToSixDpDown() {
        assertEquals("0.123456", MinimaHtlc.grain("0.123456789"));  // 7th+ dp dropped, not rounded
        assertEquals("0.123456", MinimaHtlc.grain("0.1234569"));    // would round to .123457 if UP — must stay DOWN
    }

    @Test public void grainStripsTrailingZeros() {
        assertEquals("0.15", MinimaHtlc.grain("0.150000"));
        assertEquals("1", MinimaHtlc.grain("1"));
        assertEquals("1", MinimaHtlc.grain("1.0000000"));
    }

    @Test public void grainNeverRoundsUp() {
        // For a spread of >6dp values, grain(x) must be <= x — locking more than you hold is a hard fail.
        for (String s : new String[]{"0.0000019", "9.9999999", "0.100000999", "123.4567891"}) {
            assertTrue("grain(" + s + ") must be <= " + s,
                    new BigDecimal(MinimaHtlc.grain(s)).compareTo(new BigDecimal(s)) <= 0);
        }
    }

    @Test public void grainSubGrainCollapsesToZero() {
        // Below the 6dp grain there is nothing lockable — must become 0 (the caller then declines), never dust.
        assertEquals("0", MinimaHtlc.grain("0.0000001"));   // 1e-7 < 1e-6
        assertEquals("0", MinimaHtlc.grain("0.0000009"));
    }

    @Test public void grainFallsBackOnGarbage() {
        // A non-numeric input returns unchanged rather than throwing — the node then rejects it loudly.
        assertEquals("not-a-number", MinimaHtlc.grain("not-a-number"));
    }

    @Test public void grainTruncatesTrailingFiveDown() {
        // A 7th-dp of exactly 5 must TRUNCATE (banker's/half-up rounding would go to .123457 and try to lock
        // more than the coin holds). RoundingMode.DOWN guarantees the safe direction on the exact boundary.
        assertEquals("0.123456", MinimaHtlc.grain("0.1234565"));
    }

    // ---- coinAmount(): the coloured-token value read (tokenamount, with 0x00 fallback) ----

    @Test public void coinAmountPrefersTokenAmount() throws Exception {
        // The exact shape of an mxUSDT coin: tiny `amount`, real value in `tokenamount`. MUST read the latter.
        JSONObject coin = new JSONObject()
                .put("amount", "0.000000000000000000000000000000000001")   // ~1e-36 underlying — a trap if read
                .put("tokenamount", "0.15");
        assertEquals("0.15", MinimaHtlc.coinAmount(coin));
    }

    @Test public void coinAmountIgnoresAPlausibleAmountDecoy() throws Exception {
        // The magnitude of `amount` must be irrelevant: even a plausible, non-tiny amount is NOT the value of
        // a coloured coin. This isolates the FIELD choice from the "it's tiny so it's obviously wrong" case —
        // if coinAmount ever read `amount`, this returns 0.05 and every claim/refund would misprice.
        JSONObject coin = new JSONObject().put("amount", "0.05").put("tokenamount", "0.15");
        assertEquals("0.15", MinimaHtlc.coinAmount(coin));
    }

    @Test public void coinAmountFallsBackToAmountForNative() throws Exception {
        // Native 0x00 has no tokenamount; there `amount` IS the value.
        JSONObject coin = new JSONObject().put("amount", "2.5");
        assertEquals("2.5", MinimaHtlc.coinAmount(coin));
    }

    @Test public void coinAmountHandlesMissingAndNull() throws Exception {
        assertEquals("0", MinimaHtlc.coinAmount(null));
        assertEquals("0", MinimaHtlc.coinAmount(new JSONObject()));
        assertEquals("0", MinimaHtlc.coinAmount(new JSONObject().put("amount", "").put("tokenamount", "")));
    }

    // ---- subtract() / positive(): the change arithmetic on every claim & refund ----

    @Test public void subtractComputesChange() {
        // claim(): change = coinAmount - 0.0001 notify. This is what returns to the owner; a bug burns it.
        assertEquals("0.1499", MinimaHtlc.subtract("0.15", "0.0001"));
        assertEquals("0.9999", MinimaHtlc.subtract("1", "0.0001"));
    }

    @Test public void subtractExactlyDustLeavesNoPositiveChange() {
        // A dust-only lock (amount == the 0.0001 notify) must yield NON-positive change → claim emits NO change
        // output (any zero form is fine; positive() is what the code actually branches on).
        String change = MinimaHtlc.subtract("0.0001", "0.0001");
        assertFalse("exactly-dust change must not be positive: " + change, MinimaHtlc.positive(change));
    }

    @Test public void positiveBoundaries() {
        assertTrue(MinimaHtlc.positive("0.000001"));
        assertFalse(MinimaHtlc.positive("0"));
        assertFalse(MinimaHtlc.positive("0.0000"));
        assertFalse(MinimaHtlc.positive("-0.5"));
        assertFalse(MinimaHtlc.positive("garbage"));   // exception path → treated as not-positive (no output)
        assertFalse(MinimaHtlc.positive(""));
    }

    @Test public void changeNeverExceedsInputValue() {
        // Invariant across the claim path: notify (0.0001) + change == input value, exactly. Nothing burned.
        for (String amt : new String[]{"0.15", "1", "0.0002", "12.345678"}) {
            String change = MinimaHtlc.subtract(amt, "0.0001");
            BigDecimal recombined = new BigDecimal(change).add(new BigDecimal("0.0001"));
            assertEquals("notify + change must equal input for " + amt,
                    0, recombined.compareTo(new BigDecimal(amt)));
        }
    }

    // ---- normKey(): canonical key form used for owner/receiver matching & state filters ----

    @Test public void normKeyUppercasesAndStripsPrefix() {
        assertEquals("1ABC", MinimaHtlc.normKey("0x1abc"));
        assertEquals("1ABC", MinimaHtlc.normKey("1abc"));
        assertEquals("FF", MinimaHtlc.normKey("0Xff"));
        assertEquals("", MinimaHtlc.normKey(null));
        assertEquals("ABCD", MinimaHtlc.normKey("  0xABCD  "));
    }

    // ---- stateAt(): read a coin state port from either wire shape ----

    @Test public void stateAtReadsSimplestateObject() throws Exception {
        JSONObject coin = new JSONObject().put("state", new JSONObject().put("4", "0xRECEIVER"));
        assertEquals("0xRECEIVER", MinimaHtlc.stateAt(coin, 4));
        assertEquals("", MinimaHtlc.stateAt(coin, 5));   // absent port → empty, never null
    }

    @Test public void stateAtReadsPortArray() throws Exception {
        JSONArray arr = new JSONArray()
                .put(new JSONObject().put("port", 0).put("data", "0xOWNER"))
                .put(new JSONObject().put("port", 4).put("data", "0xRECEIVER"));
        JSONObject coin = new JSONObject().put("state", arr);
        assertEquals("0xOWNER", MinimaHtlc.stateAt(coin, 0));
        assertEquals("0xRECEIVER", MinimaHtlc.stateAt(coin, 4));
        assertEquals("", MinimaHtlc.stateAt(coin, 9));
    }
}
