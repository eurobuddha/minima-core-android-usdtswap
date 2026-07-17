package com.eurobuddha.usdtswap.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * STEP: the order book payload. An {@link Order} is signed, broadcast as coin state, and parsed by every
 * peer to decide the price and size of a trade. These tests pin the (de)serialization round-trip and the
 * {@link Order#sanitize} normalizer that keeps a ladder well-formed (best-first, bounded, valid levels,
 * legacy scalars derived) — a malformed ladder either mis-prices a trade or poisons an older client.
 */
public class OrderBookTest {

    private static final String SYM = Order.PAIR_TOKENS[0];   // "USDT"

    private static Order orderWithLadder() {
        Order o = new Order();
        o.minimaPublicKey = "0xMPK";
        o.ethAddress = "0xETH";
        o.commsPublicId = "0xCID";
        o.ts = 1_700_000_000_000L;
        o.minimaAvail = 100.0;
        o.usdtAvail = 50.0;
        Order.Pair p = new Order.Pair(true, 0, 0, 0.01);
        // deliberately out-of-order to prove sanitize sorts them best-first
        p.asks.add(new Order.Level(1.03, 25));
        p.asks.add(new Order.Level(1.01, 25));
        p.asks.add(new Order.Level(1.02, 25));
        p.bids.add(new Order.Level(0.98, 25));
        p.bids.add(new Order.Level(0.99, 25));
        o.pairs.put(SYM, p);
        return o;
    }

    // ---- sanitize(): best-first ordering + derived scalars ----

    @Test public void sanitizeSortsAsksAscBidsDescAndDerivesScalars() {
        Order.Pair p = orderWithLadder().pairs.get(SYM);
        Order.sanitize(p);
        assertEquals("asks sorted ascending (best/lowest first)", 1.01, p.asks.get(0).price, 1e-9);
        assertEquals(1.02, p.asks.get(1).price, 1e-9);
        assertEquals(1.03, p.asks.get(2).price, 1e-9);
        assertEquals("bids sorted descending (best/highest first)", 0.99, p.bids.get(0).price, 1e-9);
        assertEquals(0.98, p.bids.get(1).price, 1e-9);
        assertEquals("buy scalar = best (lowest) ask", 1.01, p.buy, 1e-9);
        assertEquals("sell scalar = best (highest) bid", 0.99, p.sell, 1e-9);
    }

    @Test public void sanitizeDropsInvalidLevelsAndCapsToMax() {
        Order.Pair p = new Order.Pair(true, 0, 0, 0);
        p.asks.add(new Order.Level(0, 25));        // price 0 → invalid
        p.asks.add(new Order.Level(1.01, 0));      // amount 0 → invalid
        p.asks.add(new Order.Level(Double.NaN, 25)); // NaN → invalid
        for (int i = 0; i < Order.MAX_LEVELS + 3; i++) p.asks.add(new Order.Level(1.0 + i * 0.01, 25));
        Order.sanitize(p);
        assertTrue("no invalid levels survive", p.asks.stream().allMatch(l -> l.price > 0 && l.amount > 0));
        assertTrue("ladder capped at MAX_LEVELS", p.asks.size() <= Order.MAX_LEVELS);
    }

    @Test public void crossedDetectsBidAtOrAboveAsk() {
        Order.Pair p = new Order.Pair(true, 0, 0, 0);
        p.asks.add(new Order.Level(1.01, 25));
        p.bids.add(new Order.Level(1.05, 25));   // buying higher than selling — crossed
        Order.sanitize(p);
        assertTrue(Order.crossed(p));

        Order.Pair sane = new Order.Pair(true, 0, 0, 0);
        sane.asks.add(new Order.Level(1.02, 25));
        sane.bids.add(new Order.Level(0.98, 25));
        Order.sanitize(sane);
        assertFalse(Order.crossed(sane));
    }

    // ---- toJson()/fromJson(): the signed wire round-trip ----

    @Test public void wireRoundTripPreservesIdentityAndLadder() throws Exception {
        Order o = orderWithLadder();
        Order r = Order.fromJson(o.toJson());

        assertEquals(o.minimaPublicKey, r.minimaPublicKey);
        assertEquals(o.ethAddress, r.ethAddress);
        assertEquals(o.commsPublicId, r.commsPublicId);
        assertEquals(o.ts, r.ts);
        assertEquals(o.minimaAvail, r.minimaAvail, 1e-9);
        assertEquals(o.usdtAvail, r.usdtAvail, 1e-9);

        Order.Pair rp = r.pairs.get(SYM);
        assertTrue(rp.enable);
        assertEquals(0.01, rp.min, 1e-9);
        assertEquals(3, rp.asks.size());
        assertEquals(2, rp.bids.size());
        assertEquals(1.01, rp.asks.get(0).price, 1e-9);   // still best-first after the round-trip
        assertEquals(0.99, rp.bids.get(0).price, 1e-9);
        assertEquals(25.0, rp.asks.get(0).amount, 1e-9);
    }

    @Test public void toJsonExcludesReceiveSideFields() throws Exception {
        // signerPk + coinid are set on RECEIPT (recovered from the coin), never signed. If toJson() ever
        // included them, adding them later would change the canonical bytes and break every peer's signature
        // verification. Assert the signed payload omits them.
        Order o = orderWithLadder();
        o.signerPk = "0xSIGNERPK_SHOULD_NOT_BE_SIGNED";
        o.coinid = "0xCOINID_SHOULD_NOT_BE_SIGNED";
        JSONObject j = o.toJson();
        assertFalse(j.has("signerPk"));
        assertFalse(j.has("coinid"));
        String s = j.toString();
        assertFalse("signed bytes must not carry the receive-side signerPk", s.contains("SIGNERPK_SHOULD_NOT_BE_SIGNED"));
        assertFalse("signed bytes must not carry the receive-side coinid", s.contains("COINID_SHOULD_NOT_BE_SIGNED"));
    }

    @Test public void fromJsonToleratesGarbage() {
        // A peer must never crash on a malformed order coin — fromJson returns a best-effort empty order.
        Order r = Order.fromJson(new JSONObject());
        assertEquals("", r.minimaPublicKey);
        assertTrue(r.pairs.isEmpty() || !r.hasLiquidity());
    }

    // ---- effective levels: real ladder, or a synthetic single level from the legacy scalar ----

    @Test public void effectiveLevelsUseRealLadderWhenPresent() {
        Order o = orderWithLadder();
        Order.sanitize(o.pairs.get(SYM));
        assertEquals(3, o.effectiveAsks(SYM).size());
        assertEquals(2, o.effectiveBids(SYM).size());
        assertEquals("largest ask per-take cap", 25.0, o.maxAskAmount(SYM), 1e-9);
    }

    @Test public void effectiveAsksSynthesizeFromScalarAndBalance() {
        Order o = new Order();
        o.minimaAvail = 100.0;
        Order.Pair p = new Order.Pair(true, 1.02, 0, 0.01);   // buy scalar set, no ladder
        o.pairs.put(SYM, p);
        java.util.List<Order.Level> asks = o.effectiveAsks(SYM);
        assertEquals(1, asks.size());
        assertEquals(1.02, asks.get(0).price, 1e-9);
        assertEquals("synthetic ask size = advertised mxUSDT balance", 100.0, asks.get(0).amount, 1e-9);
    }

    @Test public void effectiveBidsSynthesizeSizeFromUsdtOverPrice() {
        Order o = new Order();
        o.usdtAvail = 50.0;
        Order.Pair p = new Order.Pair(true, 0, 0.98, 0.01);   // sell scalar set, no ladder
        o.pairs.put(SYM, p);
        java.util.List<Order.Level> bids = o.effectiveBids(SYM);
        assertEquals(1, bids.size());
        assertEquals(0.98, bids.get(0).price, 1e-9);
        assertEquals("synthetic bid size = usdtAvail / price", 50.0 / 0.98, bids.get(0).amount, 1e-6);
    }

    @Test public void disabledPairAdvertisesNothing() {
        Order o = orderWithLadder();
        o.pairs.get(SYM).enable = false;
        assertTrue(o.effectiveAsks(SYM).isEmpty());
        assertTrue(o.effectiveBids(SYM).isEmpty());
        assertFalse(o.hasLiquidity());
    }

    // ---- trimAsks(): never advertise a tranche you can't back ----

    @Test public void trimAsksKeepsFirstKAndClearsScalarWhenEmptied() {
        Order o = orderWithLadder();
        Order.sanitize(o.pairs.get(SYM));
        o.trimAsks(SYM, 1);
        assertEquals(1, o.pairs.get(SYM).asks.size());
        assertEquals("kept tranche is the best-priced", 1.01, o.pairs.get(SYM).asks.get(0).price, 1e-9);

        o.trimAsks(SYM, 0);
        assertTrue(o.pairs.get(SYM).asks.isEmpty());
        assertEquals("emptying the ask ladder must also clear the scalar so no synthetic ask resurrects",
                0.0, o.pairs.get(SYM).buy, 1e-9);
        assertTrue(o.effectiveAsks(SYM).isEmpty());
    }

    // ---- hasLiquidity(): a withdrawn order is one with no advertisable side ----

    @Test public void hasLiquidityReflectsAdvertisableSides() {
        Order o = orderWithLadder();
        Order.sanitize(o.pairs.get(SYM));
        assertTrue(o.hasLiquidity());
        o.pairs.get(SYM).asks.clear();
        o.pairs.get(SYM).bids.clear();
        o.pairs.get(SYM).buy = 0;
        o.pairs.get(SYM).sell = 0;
        assertFalse(o.hasLiquidity());
    }
}
