# Map-matching switch: manual device checklist

STATUS: NOT RUN on a phone. Item 6 was run on an emulator (49 of 49 pass, see N10 in `reports/gap_closure_log.md`); the rest is manual. The switch is verified on the JVM only (`MapMatchingToggleTest`,
`MapMatchingSwitchEngineTest`), and the evidence for map matching itself is synthetic motion on real Hyderabad road geometry.

1. Fresh install. Open the offline map screen. Expect the chip to read "Map matching off" and the caveat text (starts with "Experimental", mentions
   synthetic evidence and "next navigation session") to be visible.
2. Tap the chip. Expect "Map matching on". Force-stop the app and reopen it. Expect "Map matching on" (the choice is persisted).
3. With it on and a road graph downloaded, start a navigation session and drive or walk a known route. Expect position output at 10 Hz and no crash.
4. Turn it off during the session. Expect the running session to be unchanged; start a new session and expect it to run without the matcher.
5. With it on but no road graph downloaded, start a session. Expect normal navigation without map feedback (no error).
6. Run `connectedDebugAndroidTest` and record the pass and fail counts in `reports/gap_closure_log.md`; `EngineMapDeviceTest` expects the exact chip labels.
7. Compare a drive recorded with it on against one with it off over the same route, and report the paired difference before saying anything about benefit.
