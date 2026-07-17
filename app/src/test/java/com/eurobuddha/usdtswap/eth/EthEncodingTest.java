package com.eurobuddha.usdtswap.eth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;

/**
 * STEP: the Ethereum leg's encoding. These are the pure functions that decide WHICH on-chain contract a
 * lock/withdraw/refund targets and HOW MUCH is read back — a wrong byte here points a real transaction at
 * the wrong HTLC or misreads a counterparty's amount.
 *
 *  - {@link EthHtlc#contractId} MUST be sha256(hashlock) (NOT keccak) so it matches the Minima side, where
 *    the hashlock is SHA2(secret). Getting the hash family wrong strands the maker's USDT forever.
 *  - {@link EthHtlc#b32} coerces any hex to the 32 bytes every {@code bytes32} ABI arg needs.
 *  - {@link EthWallet#format} turns a raw integer balance into the human amount the UI and the trade grain
 *    depend on.
 *  - {@link EthHtlc#parseNew} decodes a locked-leg event; a mis-decode misreads amount/token/hashlock.
 */
public class EthEncodingTest {

    private static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";

    // ---- contractId = sha256(hashlock), deterministic, 32 bytes ----

    @Test public void contractIdIsSha256OfAllZeroHashlock() {
        // b32("0x") = 32 zero bytes; sha256(32 zero bytes) is a stable, independently-known vector.
        // This simultaneously pins the hash family (sha256, not keccak) and the zero-padding of b32.
        assertEquals("0x66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925",
                EthHtlc.contractId("0x"));
    }

    @Test public void contractIdUsesSha256NotKeccak() throws Exception {
        // Cross-check against the JDK's real SHA-256 over the same 32 bytes b32 produces. keccak256 would
        // give a different digest, so equality here is proof the impl is sha256.
        String hashlock = "0x2b6f0cc904f1f0d1e2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809";
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(EthHtlc.b32(hashlock));
        assertEquals(org.web3j.utils.Numeric.toHexString(expected), EthHtlc.contractId(hashlock));
    }

    @Test public void contractIdIsDeterministicAndCollisionSensitive() {
        String a = "0x1111111111111111111111111111111111111111111111111111111111111111";
        String b = "0x1111111111111111111111111111111111111111111111111111111111111112";
        assertEquals(EthHtlc.contractId(a), EthHtlc.contractId(a));
        assertNotEquals(EthHtlc.contractId(a), EthHtlc.contractId(b));
    }

    // ---- b32(): 32-byte coercion (left-pad short, keep LAST 32 when long) ----

    @Test public void b32LeftPadsShortInput() {
        byte[] r = EthHtlc.b32("0xff");
        assertEquals(32, r.length);
        assertEquals(0, r[0]);                 // padded high bytes are zero
        assertEquals((byte) 0xff, r[31]);      // value is right-aligned
    }

    @Test public void b32Passes32ByteInputThrough() {
        String h = "0x00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        byte[] r = EthHtlc.b32(h);
        assertEquals(32, r.length);
        assertEquals(org.web3j.utils.Numeric.toHexString(r), h);
    }

    @Test public void b32KeepsLast32BytesOfOverlongInput() {
        // 33 bytes: 0xAA followed by 32 * 0xBB. b32 must drop the leading byte and keep the trailing 32.
        StringBuilder sb = new StringBuilder("0xAA");
        for (int i = 0; i < 32; i++) sb.append("BB");
        byte[] r = EthHtlc.b32(sb.toString());
        assertEquals(32, r.length);
        for (byte b : r) assertEquals("overlong input must retain only the last 32 bytes", (byte) 0xBB, b);
    }

    @Test public void b32IsTotalOnMalformedInput() {
        // A hashlock always arrives as a 32-byte value from `random type:sha2`, but b32 must never THROW on a
        // short / empty / odd-nibble string (a throw here would abort a lock/withdraw mid-flight and could
        // strand the leg). It must always yield exactly 32 bytes.
        for (String s : new String[]{"0x", "0x0", "0xabc", "0x1", "abcd", ""}) {
            byte[] r = EthHtlc.b32(s);
            assertEquals("b32(\"" + s + "\") must be 32 bytes", 32, r.length);
        }
    }

    @Test public void contractIdIsTotalAndDeterministicOnOddLengthHashlock() {
        // Even a malformed (odd-nibble) hashlock must produce a stable 32-byte contractId, never an exception.
        String odd = "0xabc";
        String id = EthHtlc.contractId(odd);
        assertTrue(id.startsWith("0x"));
        assertEquals("0x + 64 hex", 66, id.length());
        assertEquals("deterministic", id, EthHtlc.contractId(odd));
    }

    // ---- padAddr(): 20-byte address -> 32-byte log topic ----

    @Test public void padAddrProducesLowercase32ByteTopic() {
        String topic = EthHtlc.padAddr("0xDAC17F958D2ee523a2206206994597C13D831ec7");
        assertEquals(66, topic.length());                        // 0x + 64 hex
        assertTrue(topic.startsWith("0x000000000000000000000000")); // 12 zero bytes of left-pad
        assertTrue("topic must be lowercase", topic.equals(topic.toLowerCase()));
        assertTrue(topic.endsWith("dac17f958d2ee523a2206206994597c13d831ec7"));
    }

    // ---- EthWallet.format(): raw integer -> trimmed decimal string ----

    @Test public void formatScalesAndTrims() {
        assertEquals("0.3465", EthWallet.format(BigInteger.valueOf(346500), 6, 6));   // the live 2-swap total
        assertEquals("1", EthWallet.format(BigInteger.valueOf(1_000_000), 6, 6));
        assertEquals("1.5", EthWallet.format(BigInteger.valueOf(1_500_000), 6, 6));
    }

    @Test public void formatTruncatesToMaxFractionDown() {
        // maxFrac below the token's own precision must truncate DOWN, never round up a displayed balance.
        assertEquals("1.23", EthWallet.format(BigInteger.valueOf(1_239_999), 6, 2));
    }

    @Test public void formatZeroAndNull() {
        assertEquals("0", EthWallet.format(null, 6, 6));
        assertEquals(0, new java.math.BigDecimal(EthWallet.format(BigInteger.ZERO, 6, 6)).signum());
    }

    // ---- parseNew(): decode a HTLCERC20New log into a Contract ----

    @Test public void parseNewGuardsMalformedInput() throws Exception {
        assertNull(EthHtlc.parseNew(null));
        assertNull(EthHtlc.parseNew(new JSONObject()));                        // no topics
        // fewer than 4 topics is not a valid New event
        assertNull(EthHtlc.parseNew(new JSONObject().put("topics",
                new JSONArray().put(EthHtlc.NEW_TOPIC))));
    }

    @Test public void parseNewDecodesAllFields() throws Exception {
        String owner = "0x1111111111111111111111111111111111111111";
        String receiver = "0x2222222222222222222222222222222222222222";
        String contractId = "0x3333333333333333333333333333333333333333333333333333333333333333";
        String minimaPk = "0x4444444444444444444444444444444444444444444444444444444444444444";
        String hashlock = "0x5555555555555555555555555555555555555555555555555555555555555555";
        BigInteger amount = new BigInteger("150000");        // 0.15 USDT (6dp)
        BigInteger reqAmount = new BigInteger("150000000000000000"); // 0.15 mxUSDT (18dp on the ETH side)
        BigInteger timelock = BigInteger.valueOf(1_900_000_000L);

        JSONObject log = new JSONObject();
        log.put("blockNumber", "0x10");
        log.put("transactionHash", "0xabc");
        log.put("topics", new JSONArray()
                .put(EthHtlc.NEW_TOPIC)
                .put(contractId)
                .put(pad32(owner))
                .put(pad32(receiver)));
        log.put("data", encodeNewData(minimaPk, USDT, amount, reqAmount, hashlock, timelock, false));

        EthHtlc.Contract c = EthHtlc.parseNew(log);
        assertNotNull(c);
        assertEquals(16L, c.block);
        assertEquals(contractId, c.contractId);
        assertEquals(owner.toLowerCase(), c.owner.toLowerCase());
        assertEquals(receiver.toLowerCase(), c.receiver.toLowerCase());
        assertEquals(USDT.toLowerCase(), c.tokenContract.toLowerCase());
        assertEquals(amount, c.amount);
        assertEquals(reqAmount, c.requestAmount);
        assertEquals(hashlock, c.hashlock);
        assertEquals(1_900_000_000L, c.timelock);
    }

    // -- helpers: build a real ABI-encoded New-event `data` blob and 32-byte address topics --

    /** ABI-encode the non-indexed event params exactly as the vault emits them, then strip the 4-byte
     *  function selector so what remains is the raw {@code data} field of the log. */
    private static String encodeNewData(String minimaPk, String token, BigInteger amount, BigInteger req,
                                        String hashlock, BigInteger timelock, boolean otc) {
        Function f = new Function("x", Arrays.asList(
                new Bytes32(EthHtlc.b32(minimaPk)),
                new Address(token),
                new Uint256(amount),
                new Uint256(req),
                new Bytes32(EthHtlc.b32(hashlock)),
                new Uint256(timelock),
                new Bool(otc)),
                Collections.emptyList());
        String enc = FunctionEncoder.encode(f);   // 0x + 8 selector hex + params
        return "0x" + enc.substring(10);
    }

    private static String pad32(String addr) {
        String a = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "0x000000000000000000000000" + a.toLowerCase();
    }
}
