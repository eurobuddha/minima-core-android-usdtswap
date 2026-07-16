# usdtSwap

Native Android cross-chain **atomic swaps between ERC20 USDT (Ethereum) and native USDT on Minima (mxUSDT)**.
A hash-time-locked-contract (HTLC) swap venue: trustless, non-custodial, peer-to-peer.

usdtSwap is an exact fork of **minimaSwap** with two changes:

- **Minima leg = mxUSDT**, not native MINIMA. The traded token is the bridged native-USDT token
  `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90` (the same token PandaPools uses). The ERC20
  leg is unchanged — canonical mainnet Tether (`0xdac1…ec7`, 6dp).
- **Pricing = parity.** Because both legs are USDT, the mid is fixed at **1.0**; you set a **skew** and **spread**
  around parity in the market editor (no external price feed). All other market-making, ladder, and reprice logic
  is inherited from minimaSwap unchanged.

## How it works

- **On-chain HTLC on both legs.** The Minima leg locks mxUSDT at the shared bridge HTLC contract; the Ethereum leg
  locks USDT at the bridge vault. The same SHA-256 hashlock links both — reveal to claim, or refund after the
  timelock. usdtSwap reuses minimaSwap's proven contracts and filters every scan to mxUSDT, so its coins stay
  isolated from minimaSwap's native-MINIMA coins at the same address.
- **Own order books.** usdtSwap publishes/discovers on its own on-chain sentinel channels (`USDTSWAP*`) and a
  distinct comms identity, so it never mixes with minimaSwap's live venue.
- **Runs against a local minimaCore node** (over its RPC/app transport) for the Minima leg, and keyless public
  Ethereum RPCs for the ERC20 leg. An embedded web3j wallet signs the Ethereum side; keys stay on device.

## Build

Standard Android Gradle build (JDK 17+, Android SDK 36):

```
./gradlew :app:assembleRelease
```

APKs are published to the private `eurobuddha/minima-core-apks` store, named `usdtSwap-<version>.apk`.

## Package / version

- Application id / namespace: `com.eurobuddha.usdtswap`
- Version: `0.1.0` (versionCode 100)

## Status

Ported from minimaSwap 0.17.1. On-device mainnet swap verification pending.
