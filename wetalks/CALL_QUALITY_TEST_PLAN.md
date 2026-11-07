# Call quality: manual test plan (real phones)

Status: the logic behind these features (quality levels, video pause/resume, reconnect timing, Opus SDP tuning) is unit-tested with
simulated numbers. **Nothing here has been run on a real network or a real WebRTC call yet.** This plan is how to check it.

You need: two Android phones (A = caller, B = callee) with the new build, both signed in as different users who have chatted before.
Keep `adb logcat` open on both (filter `libjingle`, `webrtc`, `Calls`) if you can.

## How to make a bad network

Pick whatever you have:

- **Hotspot + netem (best control).** Phone B joins a Linux laptop's hotspot (or a Raspberry Pi AP). On the laptop, with `wlan0` as the hotspot interface:
  - delay + jitter: `sudo tc qdisc add dev wlan0 root netem delay 300ms 100ms`
  - loss: `sudo tc qdisc change dev wlan0 root netem loss 10%`
  - 2G-like: `sudo tc qdisc change dev wlan0 root netem delay 700ms 150ms loss 3% rate 60kbit`
  - 3G-like: `sudo tc qdisc change dev wlan0 root netem delay 200ms 40ms loss 1% rate 400kbit`
  - remove: `sudo tc qdisc del dev wlan0 root`
- **Android developer options.** Settings > Developer options > "Network type preference" is not a throttle, but "Cellular data always active" plus switching to 2G/3G-only in the SIM settings works on some phones. The emulator has Extended controls > Cellular > Network type (GPRS/EDGE/UMTS) and Signal strength.
- **Real life.** Start a big upload (Drive/Photos backup) or download on the same Wi-Fi during the call, or walk to a weak-signal spot.
- **Airplane toggle / Wi-Fi switch.** For the reconnect steps: toggle Wi-Fi off/on, or turn Wi-Fi off so the phone falls back to mobile data.

Always remove the shaping before the next test.

## Tests

Time stamps are from the moment you apply the change. "Bars" = the signal bars under "End-to-end encrypted".

| # | Do this | Expected |
|---|---------|----------|
| 1 | Voice call A to B on good Wi-Fi, 1 minute. | Call connects. 4 green bars on both phones within about 6 s. Clear audio. No banner. |
| 2 | Same call, then apply 3G-like shaping to B. Hold 1 minute. | Audio stays continuous (maybe slightly less crisp). Bars drop to 2-3 and do not flicker every second. Call is not dropped. |
| 3 | Apply 2G-like shaping (700 ms, 3 % loss, 60 kbit) to B on a voice call. | Bars 1 (red). Audio still understandable (Opus FEC + DTX + 20 kbit cap), with some delay. Call stays up for 3+ minutes. |
| 4 | Video call on good Wi-Fi. | Video both ways, 4 bars. |
| 5 | During the video call apply 3G-like shaping. | Video gets softer/smaller (lower resolution, lower frame rate) but keeps moving. Audio stays clear. No banner yet (or only after a while if the rate is too low). |
| 6 | During the video call apply 2G-like shaping or 15 % loss. | Within about 6-10 s the banner "Poor connection: video paused to keep the call going" appears on the phone that sends badly. Audio keeps working. Bars go to 1. The other phone's picture of you freezes. |
| 7 | With the banner showing, remove the shaping. | After roughly 16-20 s of good network the banner disappears and video resumes by itself. Bars go back up gradually, not in one jump. |
| 8 | Video call, switch YOUR camera off with the camera button, then apply 2G shaping, then remove it. | Camera stays off the whole time (video never turns on by itself). No banner (nothing is being sent). |
| 9 | Video call, apply 2G (video pauses), then press the camera button off and on while paused. | Your choice is respected: while the network is poor the video stays paused (banner stays); when the network is good it resumes only if your camera is on. |
| 10 | Flapping: alternate shaping on/off every 5 s for 1 minute (script: `while true; do sudo tc qdisc replace dev wlan0 root netem loss 20%; sleep 5; sudo tc qdisc replace dev wlan0 root netem loss 0%; sleep 5; done`). | Bars move at most now and then (not every 2 s). Video is not paused and resumed over and over. Call is not dropped. |
| 11 | Active call, turn B's Wi-Fi off so B falls back to mobile data (or the reverse). | Within ~10 s both phones show "Reconnecting…" under the name (A may show it briefly or not at all if the switch is quick). Call comes back by itself in under ~30 s with no user action. The timer keeps running. Mute state unchanged. |
| 12 | Same as 11 but make A (caller) switch networks. | Same result. This case needs A to make the ICE restart offer, so it checks the restart path itself. |
| 13 | Active call, put B in airplane mode for 20 s, then off. | Both phones show "Reconnecting…". After B is back (about 5-15 s) the call resumes. |
| 14 | Active call, put B in airplane mode and leave it for 60 s. | Both phones show "Reconnecting…", then the call ends by itself after about 45 s (A) and up to about 55 s (B) with the message "Connection lost". Call history on both phones has the call as normal ended-call lines (same as before this change). |
| 15 | Block UDP and direct paths between the phones (e.g. a hotspot firewall that only allows TCP 443 to the TURN server) and place a call. | If a TURN server is reachable: the call connects after the second restart attempt (about 8-10 s later than normal) because the app forces the relay. If no TURN is reachable: "Connection failed" after about 45 s. See the TURN note below. |
| 16 | While in a voice call, press the Video button (add video) while shaping is active, and during a quiet period right after a Wi-Fi switch. | Video request is answered; no stuck state. Restart and video offers do not overwrite each other (the app waits up to 4 s for the other one). |
| 17 | Mute yourself, then do test 11. | You are still muted after the reconnect. |
| 18 | After any reconnect test, end the call from either side. | Call ends normally on both phones; the call line appears once in the chat and in the call history. |

## What to record

For each test: pass / fail, how many seconds until "Reconnecting…" showed, until the call came back, until the banner appeared and disappeared, and any logcat line with `IceRestart`, `setParameters`, `setConfiguration`.

## TURN note

The built-in TURN server is the public "openrelayproject" one. It is free, shared and often slow or down, so do NOT rely on test 15 passing with it. For real reliability run your own TURN (e.g. coturn) and publish it in the database at `config/turn` (the app reads it at start and keeps a copy on the phone):

```
config/turn/main = { "urls": "turn:turn.yourdomain.com:3478,turns:turn.yourdomain.com:443?transport=tcp", "username": "...", "credential": "..." }
```

`config/turn` must be written with the Firebase console or admin SDK (the app rules make it read-only for everyone). The updated `database.rules.json` has to be deployed first, otherwise the app cannot read it (it then simply uses the built-in servers).

## Known limits to keep in mind while testing

- Only the caller makes ICE-restart offers. If the CALLEE loses the network, the callee shows "Reconnecting…" and waits for the caller's next attempt (up to 3 attempts in 20 s). If the caller's attempts all fall while the callee is still offline, the call ends.
- Each phone judges its OWN sending quality, so the video pause happens on the phone with the weak uplink. The other side just sees a frozen picture; there is no banner on that side.
- The jitter buffer minimum delay (`RtpReceiver.setJitterBufferMinimumDelay`) does not exist in this WebRTC build (stream-webrtc-android 1.3.10), so it is not tuned. WebRTC's adaptive jitter buffer is left as is.
