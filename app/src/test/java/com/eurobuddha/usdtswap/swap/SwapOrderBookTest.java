package com.eurobuddha.usdtswap.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eurobuddha.comms.Hex;
import com.goterl.lazysodium.LazySodium;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * STEP: order verification — the gate every peer runs before trusting a broadcast order. A forged or
 * malformed order coin must be REJECTED (return null) so a taker never prices/locks against a fake quote.
 * The Ed25519 verify itself is native libsodium; here we mock the verifier and pin the surrounding
 * accept/reject logic — that an invalid signature, a wrong-length key/sig, or a missing field all yield null,
 * and that a valid one round-trips the signed order and stamps the recovered signer + coinid.
 */
public class SwapOrderBookTest {

    private static final int PK_LEN = 32;    // Ed25519 public key bytes
    private static final int SIG_LEN = 64;   // Ed25519 signature bytes

    private static byte[] bytes(int n, int fill) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (fill + i);
        return b;
    }

    private static String orderHex() throws Exception {
        Order o = new Order();
        o.minimaPublicKey = "0xMPK";
        o.ethAddress = "0xETH";
        o.commsPublicId = "0xCID";
        o.ts = 1_700_000_000_000L;
        o.pairs.put("USDT", new Order.Pair(true, 1.01, 0.99, 0.01));
        return Hex.to(o.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A well-formed order coin: state[99]=order JSON hex, state[1]=32-byte pk, state[2]=64-byte sig. */
    private static JSONObject coin(String orderHex, byte[] pk, byte[] sig) throws Exception {
        return new JSONObject()
                .put("coinid", "0xCOIN123")
                .put("state", new JSONObject()
                        .put("99", orderHex)
                        .put("1", "0x" + Hex.to(pk))
                        .put("2", "0x" + Hex.to(sig)));
    }

    private static LazySodium verifier(boolean result) {
        LazySodium ls = mock(LazySodium.class);
        when(ls.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class)))
                .thenReturn(result);
        return ls;
    }

    @Test public void acceptsAValidlySignedOrder() throws Exception {
        byte[] pk = bytes(PK_LEN, 1), sig = bytes(SIG_LEN, 2);
        Order o = SwapOrderBook.verifyCoin(verifier(true), coin(orderHex(), pk, sig));
        assertNotNull("a valid signature must yield the parsed order", o);
        assertEquals("0xMPK", o.minimaPublicKey);
        assertEquals("recovered signer stamped from the coin's pk", "0x" + Hex.to(pk), o.signerPk);
        assertEquals("source coinid stamped", "0xCOIN123", o.coinid);
    }

    @Test public void rejectsAForgedSignature() throws Exception {
        // Same well-formed coin, but the verifier says the signature does not check out → must be dropped.
        Order o = SwapOrderBook.verifyCoin(verifier(false), coin(orderHex(), bytes(PK_LEN, 1), bytes(SIG_LEN, 2)));
        assertNull("an order whose signature fails verification must be rejected", o);
    }

    @Test public void rejectsWrongLengthKey() throws Exception {
        // A 16-byte "key" can never be an Ed25519 pubkey — reject before even calling the verifier.
        Order o = SwapOrderBook.verifyCoin(verifier(true), coin(orderHex(), bytes(16, 1), bytes(SIG_LEN, 2)));
        assertNull(o);
    }

    @Test public void rejectsWrongLengthSignature() throws Exception {
        Order o = SwapOrderBook.verifyCoin(verifier(true), coin(orderHex(), bytes(PK_LEN, 1), bytes(32, 2)));
        assertNull(o);
    }

    @Test public void rejectsMissingOrderPayload() throws Exception {
        // No state[99] → nothing to verify. Must be null and must not touch the verifier (pass null to prove it).
        JSONObject c = new JSONObject().put("coinid", "0xC")
                .put("state", new JSONObject().put("1", "0x" + Hex.to(bytes(PK_LEN, 1)))
                        .put("2", "0x" + Hex.to(bytes(SIG_LEN, 2))));
        assertNull(SwapOrderBook.verifyCoin(null, c));
    }

    @Test public void rejectsNullCoin() {
        assertNull(SwapOrderBook.verifyCoin(null, null));
    }
}
