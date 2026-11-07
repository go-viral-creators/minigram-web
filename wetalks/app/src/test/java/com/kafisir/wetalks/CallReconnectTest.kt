package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import org.junit.Assert.*
import org.junit.Test

/** The reconnect policy driven with a simulated clock (ms). The printed timelines show every decision. */
class CallReconnectTest {
    private class Sim(caller: Boolean) {
        val m = ReconnectMachine(caller)
        var t = 0L
        val log = ArrayList<String>()
        val all = ArrayList<Pair<Long, ReconnectAction>>()
        private fun rec(a: List<ReconnectAction>) = a.forEach { all += t to it; log += "t=${t / 1000.0}s ${it::class.simpleName}" + ((it as? ReconnectAction.RestartIce)?.let { r -> "(attempt ${r.attempt}, relay=${r.relay}, silent=${r.silent})" } ?: "") }
        fun ice(e: IceEv) { log += "t=${t / 1000.0}s [ice $e]"; rec(m.onIce(e, t)) }
        fun net(avail: Boolean) { log += "t=${t / 1000.0}s [network ${if (avail) "available/changed" else "lost"}]"; rec(m.onNetwork(avail, t)) }
        /** Advance the clock in 500 ms ticks, like the real ticker. */
        fun run(ms: Long) { val end = t + ms; while (t < end) { t += 500; rec(m.onTick(t)) } }
        fun restarts() = all.map { it.second }.filterIsInstance<ReconnectAction.RestartIce>()
        fun at(a: ReconnectAction) = all.first { it.second == a }.first
        fun print(name: String) = println("[$name]\n  " + log.joinToString("\n  "))
    }

    @Test fun callerFailedTwiceThenGivesUpAfter45s() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.run(5_000)
        s.ice(IceEv.FAILED); s.run(60_000)
        s.print("caller, link dead for good")
        val r = s.restarts()
        assertEquals(3, r.size)
        assertEquals(listOf(1, 2, 3), r.map { it.attempt })
        assertEquals("first attempt on the normal path, then forced relay", listOf(false, true, true), r.map { it.relay })
        assertTrue(r.none { it.silent })
        assertEquals(45_000L + 5_000L, s.at(ReconnectAction.GiveUp))
        assertEquals(1, s.all.count { it.second == ReconnectAction.GiveUp })
        // attempt times: 0 s, 8 s, 20 s after the failure
        val times = s.all.filter { it.second is ReconnectAction.RestartIce }.map { it.first - 5_000L }
        assertEquals(listOf(500L, 8_500L, 20_500L), times.map { it })   // +500 ms: the first tick after the event
    }

    @Test fun disconnectedWaitsTwoSecondsForSelfHealing() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.DISCONNECTED); s.run(1_500); s.ice(IceEv.CONNECTED); s.run(60_000)
        s.print("caller, DISCONNECTED that heals by itself")
        assertTrue("no restart at all", s.restarts().isEmpty())
        assertTrue(s.all.any { it.second == ReconnectAction.ShowReconnecting }); assertTrue(s.all.any { it.second == ReconnectAction.HideReconnecting })
        assertEquals(0, s.all.count { it.second == ReconnectAction.GiveUp }); assertEquals(ReconnectMachine.State.IDLE, s.m.state)
    }

    @Test fun reconnectedAfterFirstRestartKeepsTheNormalPathThenReturnsToNormal() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.DISCONNECTED); s.run(3_000)
        assertEquals(1, s.restarts().size); assertFalse(s.restarts()[0].relay)
        s.ice(IceEv.CONNECTED); s.run(60_000)
        s.print("caller, back after attempt 1")
        assertEquals(1, s.restarts().size); assertFalse(s.all.any { it.second == ReconnectAction.RelayOff }); assertEquals(0, s.all.count { it.second == ReconnectAction.GiveUp })
    }

    @Test fun relayIsForcedAfterTheFirstRestartFailsAndGoesBackToAllLater() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.FAILED); s.run(9_000)       // attempt 1 at 0 s, attempt 2 (relay) at 8 s
        assertEquals(listOf(false, true), s.restarts().map { it.relay })
        s.ice(IceEv.CONNECTED)                                          // the relay path works
        s.run(19_000); assertFalse(s.all.any { it.second == ReconnectAction.RelayOff })
        s.run(2_000);  assertTrue("policy goes back to ALL once the call has been stable for 20 s", s.all.any { it.second == ReconnectAction.RelayOff })
        s.print("caller, relay worked")
    }

    @Test fun calleeNeverRestartsAndGivesUpLater() {
        val s = Sim(false)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.FAILED); s.run(70_000)
        s.print("callee, link dead")
        assertTrue(s.restarts().isEmpty())
        assertTrue(s.all.any { it.second == ReconnectAction.ShowReconnecting })
        assertEquals("callee waits 10 s longer than the caller's window", 55_000L, s.at(ReconnectAction.GiveUp))
    }

    @Test fun calleeNetworkChangeWhileConnectedDoesNothing() {
        val s = Sim(false)
        s.ice(IceEv.CONNECTED); s.net(true); s.run(10_000)
        assertTrue(s.all.isEmpty())
    }

    @Test fun wifiToMobileSwitchWithAGapOfSeveralSeconds() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.run(10_000)
        s.net(false)                                  // Wi-Fi lost
        s.run(1_000); s.ice(IceEv.DISCONNECTED)       // media stops, ICE notices
        s.run(5_000)                                  // 5 s without any network: attempts are held
        val before = s.restarts().size
        assertEquals("no restart while offline", 0, before)
        s.net(true)                                   // mobile data is up
        s.run(2_000)
        s.print("caller, Wi-Fi -> mobile with a 6 s gap")
        assertTrue("restarts at once when the network is back", s.restarts().size >= 1)
        s.ice(IceEv.CONNECTED); s.run(40_000)
        assertEquals(0, s.all.count { it.second == ReconnectAction.GiveUp }); assertEquals(ReconnectMachine.State.IDLE, s.m.state)
    }

    @Test fun networkChangeWhileStillConnectedMakesOneQuietRestart() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.run(10_000)
        s.net(true); s.net(true); s.net(true)         // several callbacks for one change
        s.run(3_000)
        s.print("caller, quiet restart on a network change")
        val r = s.restarts()
        assertEquals(1, r.size); assertTrue(r[0].silent); assertEquals(0, r[0].attempt)
        assertFalse("no banner for a quiet restart", s.all.any { it.second == ReconnectAction.ShowReconnecting })
        s.net(true); s.run(2_000)
        assertEquals("not again within 5 s", 1, s.restarts().size)
        s.run(6_000)                                  // the change signalled at 13 s is carried out once the 5 s gap has passed
        assertEquals(2, s.restarts().size)
    }

    @Test fun networkChangeBeforeTheCallEverConnectedIsIgnored() {
        val s = Sim(true)
        s.net(true); s.run(5_000)
        assertTrue(s.all.isEmpty())
    }

    @Test fun flappingLinkNeverEndsTheCallAndNeverRestartsMoreThanBounded() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED)
        repeat(10) { s.ice(IceEv.DISCONNECTED); s.run(1_000); s.ice(IceEv.CONNECTED); s.run(1_000) }
        s.print("caller, flapping every 2 s")
        assertEquals(0, s.all.count { it.second == ReconnectAction.GiveUp })
        assertTrue("at most one restart per trouble episode, here none (DISCONNECTED heals within the 2 s grace)", s.restarts().size <= 10)
    }

    @Test fun aSecondTroubleAfterRecoveryGetsAFreshWindow() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.FAILED); s.run(30_000); s.ice(IceEv.CONNECTED); s.run(10_000)
        val first = s.restarts().size
        s.ice(IceEv.FAILED); s.run(60_000)
        assertTrue(s.restarts().size > first)
        assertEquals(1, s.all.count { it.second == ReconnectAction.GiveUp })
        assertEquals(ReconnectMachine.State.GAVE_UP, s.m.state)
    }

    @Test fun afterGivingUpNothingFiresAgain() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.FAILED); s.run(50_000)
        val n = s.all.size
        s.ice(IceEv.CONNECTED); s.net(true); s.run(10_000)
        assertEquals(n, s.all.size)
    }

    @Test fun failedBeforeTheFirstConnectionAlsoTriesRestartsAndRelay() {
        val s = Sim(true)
        s.ice(IceEv.FAILED); s.run(30_000)
        s.print("caller, ICE never connected")
        assertEquals(listOf(false, true, true), s.restarts().map { it.relay })
    }

    @Test fun failedWhileWaitingBringsTheNextAttemptForward() {
        val s = Sim(true)
        s.ice(IceEv.CONNECTED); s.ice(IceEv.DISCONNECTED); s.run(2_500)    // attempt 1 done at 2 s
        s.run(3_000)                                                        // at 5.5 s the link is declared failed: attempt 2 should not wait for 10 s
        s.ice(IceEv.FAILED); s.run(1_000)
        s.print("caller, FAILED while waiting")
        assertEquals(2, s.restarts().size)
        assertTrue(s.at(s.all.filter { it.second is ReconnectAction.RestartIce }[1].second) < 8_000)
    }
}
