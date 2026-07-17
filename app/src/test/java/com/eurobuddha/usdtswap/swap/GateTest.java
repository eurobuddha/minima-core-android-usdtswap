package com.eurobuddha.usdtswap.swap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.usdtswap.eth.EthHtlc;
import com.eurobuddha.usdtswap.eth.EthNet;
import com.eurobuddha.usdtswap.eth.EthRpc;
import com.eurobuddha.usdtswap.eth.EthWallet;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;

/**
 * STEP: the responder acceptance gates — the code's own "fund-safety boundary". Before the engine locks a
 * counter-leg it must reject any deal that is mispriced, below the maker's minimum, or over a tranche cap
 * (auto-ladder), and any OTC lock that doesn't EXACTLY match the agreed terms / addresses / token. A hole
 * here means auto-locking real funds against a bad price — the most direct fund-loss path in the app.
 *
 * The gate methods are exercised on a real {@link SwapEngine} whose six dependencies are Mockito-doubled
 * (the constructor does no Android work); {@code net} is the real mainnet config so token/decimals resolve.
 */
public class GateTest {

    private static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String MY_ETH = "0x1111111111111111111111111111111111111111";
    private static final String MY_MPK = "0xAAAA";
    private static final String PEER_MPK = "0xBBBB";
    private static final String PEER_ETH = "0x2222222222222222222222222222222222222222";
    private static final BigInteger MX_0_2 = new BigInteger("200000000000000000");   // 0.2 mxUSDT (18dp)
    private static final BigInteger USDT_0_2 = BigInteger.valueOf(200000);           // 0.2 USDT (6dp)

    private SwapEngine engine;

    @Before public void setUp() {
        EthWallet wallet = mock(EthWallet.class);
        when(wallet.address()).thenReturn(MY_ETH);
        engine = new SwapEngine(mock(NodeApi.class), mock(MinimaHtlc.class), mock(SwapDb.class),
                wallet, mock(Handler.class), mock(SwapEngine.Notifier.class));
        engine.setNetwork(new EthRpc(EthNet.MAINNET.defaultRpc), EthNet.MAINNET);
        engine.setMyMinimaPk(MY_MPK);
        // My order: a 2-sided ladder at parity (1.0 USDT per mxUSDT), 25 per-take cap, 0.01 minimum.
        Order o = new Order();
        Order.Pair p = new Order.Pair(true, 0, 0, 0.01);
        p.asks.add(new Order.Level(1.0, 25));   // I SELL mxUSDT at 1.0
        p.bids.add(new Order.Level(1.0, 25));   // I BUY mxUSDT at 1.0
        o.pairs.put("USDT", p);
        engine.setMyOrder(o);
    }

    // ---- acceptTakerSellMinima: taker sells mxUSDT, I BUY (bid ladder) ----

    private static JSONObject sellCoin(String mxLocked, String usdtRequested) throws Exception {
        return new JSONObject()
                .put("tokenid", MinimaHtlc.USDT_TOKENID)
                .put("tokenamount", mxLocked)                    // mxUSDT they locked, I receive
                .put("state", new JSONObject().put("1", usdtRequested));  // USDT they requested, I'd pay
    }

    @Test public void acceptsAFairSell() throws Exception {
        assertTrue("0.2 mxUSDT for 0.2 USDT at parity must be accepted",
                engine.acceptTakerSellMinima(sellCoin("0.2", "0.2"), USDT));
    }

    @Test public void rejectsAnOverpricedSell() throws Exception {
        assertFalse("asking 0.25 USDT for 0.2 mxUSDT (above my 1.0 bid) must be rejected",
                engine.acceptTakerSellMinima(sellCoin("0.2", "0.25"), USDT));
    }

    @Test public void rejectsBelowMinimumSell() throws Exception {
        assertFalse("0.005 mxUSDT is below the 0.01 minimum",
                engine.acceptTakerSellMinima(sellCoin("0.005", "0.005"), USDT));
    }

    @Test public void rejectsOverCapSell() throws Exception {
        assertFalse("30 mxUSDT exceeds the 25 per-take cap of every tranche",
                engine.acceptTakerSellMinima(sellCoin("30", "30"), USDT));
    }

    @Test public void acceptsASellThatFavoursMe() throws Exception {
        assertTrue("taker asking only 0.1 USDT for 0.2 mxUSDT is strictly better for me",
                engine.acceptTakerSellMinima(sellCoin("0.2", "0.1"), USDT));
    }

    // ---- acceptTakerBuyMinima: taker buys mxUSDT, I SELL (ask ladder) ----

    private static EthHtlc.Contract buyContract(BigInteger usdtLocked, BigInteger mxRequested) {
        EthHtlc.Contract c = new EthHtlc.Contract();
        c.tokenContract = USDT;
        c.amount = usdtLocked;          // USDT they locked, I receive
        c.requestAmount = mxRequested;  // mxUSDT they want, I'd give
        return c;
    }

    @Test public void acceptsAFairBuy() {
        assertTrue("locking 0.2 USDT for 0.2 mxUSDT at parity must be accepted",
                engine.acceptTakerBuyMinima(buyContract(USDT_0_2, MX_0_2)));
    }

    @Test public void rejectsAnUnderpaidBuy() {
        assertFalse("locking only 0.15 USDT for 0.2 mxUSDT (below my 1.0 ask) must be rejected",
                engine.acceptTakerBuyMinima(buyContract(BigInteger.valueOf(150000), MX_0_2)));
    }

    // ---- otcVerifyBuy: instigator BUYS mxUSDT; I (LP) verify the USDT lock EXACTLY matches the agreed deal ----

    private static OtcDb.Deal buyDeal() {
        OtcDb.Deal d = new OtcDb.Deal();
        d.side = OtcOffer.LP_SELLS_MINIMA;
        d.amount = "0.2"; d.price = "1.0";
        d.peerMinimaPk = PEER_MPK; d.peerEthAddr = PEER_ETH;
        return d;
    }

    private static EthHtlc.Contract otcBuyContract() {
        EthHtlc.Contract c = new EthHtlc.Contract();
        c.tokenContract = USDT;
        c.receiver = MY_ETH;            // USDT addressed to me
        c.minimaPublicKey = PEER_MPK;   // mxUSDT goes to the agreed peer
        c.amount = USDT_0_2;            // 0.2 USDT locked
        c.requestAmount = MX_0_2;       // 0.2 mxUSDT requested from me
        return c;
    }

    @Test public void otcBuyAcceptsAnExactMatch() {
        assertTrue(engine.otcVerifyBuy(otcBuyContract(), buyDeal()));
    }

    @Test public void otcBuyRejectsAmountMismatch() {
        EthHtlc.Contract c = otcBuyContract();
        c.amount = BigInteger.valueOf(150000);   // 0.15 USDT ≠ agreed 0.2
        assertFalse(engine.otcVerifyBuy(c, buyDeal()));
    }

    @Test public void otcBuyRejectsWrongReceiver() {
        EthHtlc.Contract c = otcBuyContract();
        c.receiver = PEER_ETH;   // USDT NOT addressed to me
        assertFalse(engine.otcVerifyBuy(c, buyDeal()));
    }

    @Test public void otcBuyRejectsWrongToken() {
        EthHtlc.Contract c = otcBuyContract();
        c.tokenContract = "0xdeadbeef00000000000000000000000000000000";
        assertFalse("must settle in the real USDT contract", engine.otcVerifyBuy(c, buyDeal()));
    }

    // ---- otcVerifySell: instigator SELLS mxUSDT; I (LP) verify the mxUSDT lock matches the agreed deal ----

    private static OtcDb.Deal sellDeal() {
        OtcDb.Deal d = new OtcDb.Deal();
        d.side = OtcOffer.LP_BUYS_MINIMA;
        d.amount = "0.2"; d.price = "1.0";
        d.peerMinimaPk = PEER_MPK; d.peerEthAddr = PEER_ETH;
        return d;
    }

    private static JSONObject otcSellCoin() throws Exception {
        return new JSONObject()
                .put("tokenid", MinimaHtlc.USDT_TOKENID)
                .put("tokenamount", "0.2")       // mxUSDT they locked
                .put("state", new JSONObject()
                        .put("1", "0.2")          // USDT they request = what I lock
                        .put("4", MY_MPK)         // mxUSDT addressed to me
                        .put("6", PEER_ETH));     // I pay USDT to the agreed eth
    }

    @Test public void otcSellAcceptsAnExactMatch() throws Exception {
        assertTrue(engine.otcVerifySell(otcSellCoin(), sellDeal(), USDT));
    }

    @Test public void otcSellRejectsWrongToken() throws Exception {
        JSONObject coin = otcSellCoin().put("tokenid", "0x00");   // native MINIMA, not mxUSDT
        assertFalse("must be the mxUSDT token", engine.otcVerifySell(coin, sellDeal(), USDT));
    }

    @Test public void otcSellRejectsCoinNotAddressedToMe() throws Exception {
        JSONObject coin = otcSellCoin();
        coin.getJSONObject("state").put("4", "0xCCCC");   // mxUSDT to someone else
        assertFalse(engine.otcVerifySell(coin, sellDeal(), USDT));
    }
}
