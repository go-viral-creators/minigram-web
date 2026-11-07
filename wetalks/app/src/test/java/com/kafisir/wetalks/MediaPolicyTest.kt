package com.kafisir.wetalks

import com.kafisir.wetalks.data.MediaPolicy
import com.kafisir.wetalks.data.MediaPolicy.Net
import com.kafisir.wetalks.data.MediaPolicy.NetState
import com.kafisir.wetalks.data.MediaPolicy.Type
import com.kafisir.wetalks.data.MediaPolicy.Why
import org.junit.Assert.*
import org.junit.Test

class MediaPolicyTest {
    private val d = MediaPolicy.defaults()
    private val wifi = NetState(Net.WIFI)
    private val mobile = NetState(Net.MOBILE, metered = true)
    private val roam = NetState(Net.ROAMING, metered = true)
    private val mb = 1048576L
    private fun auto(t: Type, size: Long, n: NetState, s: MediaPolicy.Settings = d, cached: Boolean = false, mine: Boolean = false) = MediaPolicy.decide(t, size, n, s, cached, mine)

    @Test fun defaultsWifiEverythingUpToLimit() {
        for (t in Type.values()) assertTrue(t.name, auto(t, 2 * mb, wifi).auto)
        assertFalse(auto(Type.VIDEO, 26 * mb, wifi).auto)
        assertEquals(Why.TOO_BIG, auto(Type.VIDEO, 26 * mb, wifi).why)
        assertTrue(auto(Type.VIDEO, 25 * mb, wifi).auto)
    }

    @Test fun defaultsMobilePhotosAndVoiceOnly() {
        assertTrue(auto(Type.PHOTO, mb, mobile).auto)
        assertTrue(auto(Type.VOICE, mb, mobile).auto)
        for (t in listOf(Type.AUDIO, Type.VIDEO, Type.DOC, Type.GIF)) assertEquals(Why.TYPE_OFF, auto(t, 1000, mobile).why)
    }

    @Test fun defaultsRoamingNothing() {
        for (t in Type.values()) assertFalse(t.name, auto(t, 100, roam).auto)
    }

    @Test fun offlineNeverAutoButCachedAndOwnStillShow() {
        val off = NetState(Net.OFFLINE)
        assertEquals(Why.OFFLINE, auto(Type.PHOTO, 100, off).why)
        assertTrue(auto(Type.PHOTO, 100, off, cached = true).auto)
        assertTrue(auto(Type.VIDEO, 100 * mb, off, mine = true).auto)
    }

    @Test fun cachedAndSentByMeAreNeverBlocked() {
        val nothing = MediaPolicy.Settings(MediaPolicy.Rule(emptySet(), 1), MediaPolicy.Rule(emptySet(), 1), MediaPolicy.Rule(emptySet(), 1), useLessData = true)
        for (n in listOf(wifi, mobile, roam)) for (t in Type.values()) {
            assertEquals(Why.CACHED, auto(t, 500 * mb, n, nothing, cached = true).why)
            assertEquals(Why.SENT_BY_ME, auto(t, 500 * mb, n, nothing, mine = true).why)
        }
    }

    @Test fun unknownSizeWaitsForTapOnlyWhenALimitApplies() {
        assertEquals(Why.SIZE_UNKNOWN, auto(Type.VIDEO, 0, wifi).why)
        assertEquals(Why.SIZE_UNKNOWN, auto(Type.DOC, -1, wifi).why)
        assertTrue(auto(Type.PHOTO, 0, wifi).auto)          // photos and voice have no limit
        assertTrue(auto(Type.VOICE, -1, mobile).auto)
        val noLimit = d.withRule(Net.WIFI, d.wifi.copy(maxMb = 0))
        assertTrue(auto(Type.VIDEO, 0, wifi, noLimit).auto)  // no limit set: unknown size is fine
    }

    @Test fun mobileLimitIsSeparateFromWifiLimit() {
        val s = d.withRule(Net.MOBILE, d.mobile.with(Type.DOC, true).copy(maxMb = 5))
        assertTrue(auto(Type.DOC, 4 * mb, mobile, s).auto)
        assertEquals(Why.TOO_BIG, auto(Type.DOC, 6 * mb, mobile, s).why)
        assertTrue(auto(Type.DOC, 20 * mb, wifi, s).auto)
    }

    @Test fun roamingWithMeteredUsesRoamingRule() {
        val s = d.withRule(Net.MOBILE, d.mobile.with(Type.VIDEO, true)).withRule(Net.ROAMING, d.roaming.with(Type.PHOTO, true))
        assertTrue(auto(Type.PHOTO, mb, roam, s).auto)
        assertFalse(auto(Type.VIDEO, mb, roam, s).auto)     // allowed on home mobile data, not while roaming
    }

    @Test fun useLessDataKeepsOnlyVoiceOnPaidNetworks() {
        val s = d.copy(useLessData = true)
        assertTrue(auto(Type.VOICE, mb, mobile, s).auto)
        assertEquals(Why.LESS_DATA, auto(Type.PHOTO, mb, mobile, s).why)
        // on Wi-Fi photos still come, but big files are capped at 5 MB
        assertTrue(auto(Type.PHOTO, mb, wifi, s).auto)
        assertTrue(auto(Type.VIDEO, 5 * mb, wifi, s).auto)
        assertEquals(Why.TOO_BIG, auto(Type.VIDEO, 6 * mb, wifi, s).why)
    }

    @Test fun useLessDataCapsEvenWhenNoLimitWasSetOnWifi() {
        val s = d.withRule(Net.WIFI, d.wifi.copy(maxMb = 0)).copy(useLessData = true)
        assertEquals(Why.TOO_BIG, auto(Type.DOC, 6 * mb, wifi, s).why)
        assertEquals(Why.SIZE_UNKNOWN, auto(Type.DOC, 0, wifi, s).why)
    }

    @Test fun systemDataSaverOnlyAffectsPaidNetworks() {
        val saver = mobile.copy(systemDataSaver = true)
        assertEquals(Why.DATA_SAVER, auto(Type.PHOTO, mb, saver).why)
        assertTrue(auto(Type.VOICE, mb, saver).auto)
        assertTrue(auto(Type.PHOTO, mb, wifi.copy(systemDataSaver = true)).auto)
    }

    @Test fun meteredWifiCountsAsMobileData() {
        val hotspot = MediaPolicy.classify(wifiOrEthernet = true, cellular = false, notRoaming = true, notMetered = false)
        assertEquals(Net.MOBILE, hotspot.net); assertTrue(hotspot.metered)
        assertFalse(auto(Type.VIDEO, mb, hotspot).auto)
    }

    @Test fun classifyEveryNetwork() {
        assertEquals(Net.WIFI, MediaPolicy.classify(true, false, true, true).net)
        assertEquals(Net.MOBILE, MediaPolicy.classify(false, true, true, false).net)
        assertEquals(Net.ROAMING, MediaPolicy.classify(false, true, false, false).net)
        assertEquals(Net.ROAMING, MediaPolicy.classify(false, true, false, true).net)
        assertEquals(Net.OFFLINE, MediaPolicy.classify(false, false, true, true).net)
        assertTrue(MediaPolicy.classify(false, true, true, true, systemDataSaver = true).systemDataSaver)
    }

    @Test fun settingsChangedWhileDownloading() {
        // started on Wi-Fi by itself, then the phone moves to mobile data: stop (video is not allowed there)
        assertFalse(MediaPolicy.keepGoing(false, Type.VIDEO, 10 * mb, mobile, d))
        // the person tapped it: it carries on
        assertTrue(MediaPolicy.keepGoing(true, Type.VIDEO, 10 * mb, roam, d))
        // an automatic photo download carries on when the rules still allow it
        assertTrue(MediaPolicy.keepGoing(false, Type.PHOTO, mb, mobile, d))
        // the person turns photos off in the settings mid-download
        assertFalse(MediaPolicy.keepGoing(false, Type.PHOTO, mb, mobile, d.withRule(Net.MOBILE, d.mobile.with(Type.PHOTO, false))))
        // my own upload is never stopped
        assertTrue(MediaPolicy.keepGoing(false, Type.PHOTO, mb, roam, d, mine = true))
    }

    @Test fun roamingWarningOnlyWhenRoaming() {
        assertTrue(MediaPolicy.warnOnTap(roam)); assertFalse(MediaPolicy.warnOnTap(mobile)); assertFalse(MediaPolicy.warnOnTap(wifi))
    }

    @Test fun saveAndLoadRoundTrip() {
        val s = d.withRule(Net.MOBILE, d.mobile.with(Type.VIDEO, true).copy(maxMb = 10)).copy(useLessData = true)
        assertEquals(s, MediaPolicy.decode(MediaPolicy.encode(s)))
        assertEquals(d, MediaPolicy.decode(MediaPolicy.encode(d)))
    }

    @Test fun brokenSavedTextFallsBackToDefaults() {
        assertEquals(d, MediaPolicy.decode(null)); assertEquals(d, MediaPolicy.decode("")); assertEquals(d, MediaPolicy.decode("garbage"))
        val half = MediaPolicy.decode("v2;w=PHOTO|zz;m=;r=NOPE,PHOTO|3;l=1")
        assertEquals(d.wifi.maxMb, half.wifi.maxMb)               // bad number: default limit kept
        assertEquals(setOf(Type.PHOTO), half.wifi.types)
        assertEquals(setOf(Type.PHOTO), half.roaming.types)       // unknown type name ignored
        assertEquals(3, half.roaming.maxMb); assertTrue(half.useLessData)
        assertEquals(30, MediaPolicy.decode("v2;w=PHOTO|999").wifi.maxMb)
    }

    @Test fun legacyPhotoChoiceIsKept() {
        assertTrue(MediaPolicy.migrateLegacy("always").mobile.has(Type.PHOTO))
        assertFalse(MediaPolicy.migrateLegacy("wifi").mobile.has(Type.PHOTO))
        assertTrue(MediaPolicy.migrateLegacy("wifi").wifi.has(Type.PHOTO))
        val never = MediaPolicy.migrateLegacy("never")
        assertFalse(never.wifi.has(Type.PHOTO)); assertFalse(never.mobile.has(Type.PHOTO))
    }

    @Test fun messageTypesMapToMediaTypes() {
        assertEquals(Type.PHOTO, MediaPolicy.typeOf("image")); assertEquals(Type.DOC, MediaPolicy.typeOf("file"))
        assertEquals(Type.GIF, MediaPolicy.typeOf("gif")); assertNull(MediaPolicy.typeOf("text")); assertNull(MediaPolicy.typeOf("sticker"))
    }
}
