# Candidate: Telegram Android motion redesign 1.9.0

Baseline: DrKLO/Telegram 7be672ead283d022812d75c729d3a04dd4c2a2c6, 13.2.1 (7291). Independent Compose implementation using the updated 31-recipe specification; no Telegram code/resources copied. The original transit data, identity, line graphics and transport tasks remain.

## Interaction contracts

| Component | Reference | Trigger and progress | Finish / cancellation | Interrupt and lifetime |
|---|---|---|---|---|
| Header actions | M01 / ButtonBounce | pressed interaction, 60ms compress; 350ms Android Overshoot(5), caller diff .035 | release clicks immediately; pointer cancellation only releases visual | animateFloat starts from current value; first binding settled; effect lifetime follows component |
| Transport / setting rows | M02 / RecyclerListView | Compose bounded touch-hotspot indication; no whole-row scaling | child controls consume once; vertical scroll cancels pending click | stable IDs and normal gesture arbitration |
| Search | M06–08 | 150ms route/container crossfade; focus/IME after entering. Clear 180ms alpha+scale+45deg. Same cross/arc geometry cycles720deg/1000ms | clear retains focus; stop loading finishes cycle; keyboard return owns the first back | query generation and cancellation checked after every async boundary; elapsed-time animation stops when disposed. Additional-button translation not applicable: no extra search button |
| Arrival / setting text | M09/M10/M30 | LCS matched glyphs retain outlines and interpolate positions; changed glyphs slide/fade; width follows same progress. Settings320ms .55em quint; ordinary numeric arrivals150ms1em | semantic target immediate; old layouts discarded when settled | conflated latest pending target, no unbounded animation queue; first composition settled |
| Station preview sheet | M15/M16 | measured400ms quint entry,20ms delay; shared offset drives dim; physical .8cm threshold,3500px/s, child-first nested scroll | short slow pull returns250*Y/D; cancellation safe; close once | handle gesture cancels entry; no platform sheet animation; nested fling assumes horizontal velocity0 since it is vertical input |
| System back | M18 / ActionBarLayout1681–1750 | system progress drives56dp*StandardDecelerate; previous page snapshot behind | cancel320ms return; system commit380ms toW+56dp, pop exactly once | generation/entry guards reject obsolete completion. Keyboard and dialog windows own local back before page. Legacy button back is a separate200ms page transition; ordinary full-page swipe M17 not added |
| Recent history | M19/M31 | foreground follows full down-to-current dx; .45W boundary haptic;300ms circular reveal;170ms glyph bounce; stable-key180ms list compensation | below threshold/cancel return200ms; commit starts actual delete immediately;3500px/s escape | vertical children win before slop; cancel never deletes; per-key state cannot leak to next row |
| Main navigation | M22/M23/M24/M30 | shared selectedFactor320ms decelerate drives label/icon color and actual path contour/hole interpolation; parent long press .75*platform timeout; touch-following lens and pivot;1.019 scale380ms; low-bouncy medium spring release | release commits nearest destination through normal callback; cancel no navigation; reselect scrolls current destination to top | no fabricated child menus; first-binding selection settled; no input lock while selection animates |
| Undo feedback | M25/M26 | operationID + retained actual persistence snapshot; host spring stiffness400 damping .8,5000ms accessibility-adjusted deadline | undo restores real DataStore records; timeout/replacement/detach commits. Exactly one terminal state per operation | replace policy commits old A, exposes undo for B only; callbacks idempotent, history ordering and new additions preserved; route detach clears host |
| Update row | M27 |6dp/3dp radius loading line at value anchor;150ms opposing value alpha,1000ms pulse | success returns to glyph-diff value; failure retains row and error state | local pulse disposed on stop; no group-wide spinner |

## Materials, resources and evidence limits

Navigation samples a separately recorded content GraphicsLayer and applies API31+ RenderEffect blur under a clipped translucent capsule. Below API31 or software canvas the solid semantic fallback is explicit. The sampler is never recorded into itself. This is a platform blur approximation, not Telegram proprietary lens refraction/shader equivalence. Icons are independent business-appropriate contour paths, not original RLottie resources. No original Telegram screen recording was available, so no pixel-perfect or dynamically aligned claim is made.

The tool has no account/profile photo, no check/radio settings, no unread counters, no download task or batch selection; M03–05/M11–14/M17/M20–21/M28–29 are not invented merely to showcase recipes. Business extensions and fallback geometry are ADAPTATION, while stated timing/threshold specifications come from SOURCE.

Package com.yll682.houche.telegram reuses the previous redesign signing key and increments versionCode23, allowing upgrade from1.8.0 while retaining its data.

## Verification record

Release assemble and lint-vital succeeded. Final behavior run: 18 tests passed (9 motion/gesture/shape,3 navigation/settings/history interaction,3 persistence/race,1 system predictive-back,2 launch). Separately,12 light/dark/narrow/large-font screen renders passed; the final callback fix does not change their geometry.

Captured target-app intermediate frames include navigation0/25/50/75/100, clear-to-arc and restoration, and changed-glyph text. Tested cancellation for press, navigation lens, row swipe and sheet; crossed swipe .44W→.46W→.44W without deletion; dispatch-tested system back .5 progress→cancel→commit. Undo is restored from DataStore, including immediately retiring the originating ViewModel; stale search responses cannot replace a new query or refill after clear.

Found and corrected the reselect handler retaining its first captured selection; its stable callback now reads rememberUpdatedState. Gesture tests also caught the consumed synthetic-cancel path, which no longer commits a tab. Interrupted page destruction skips non-cancellable return animation to avoid waiting for frames from a destroyed view.

No reference Telegram recording was compared. Runtime screenshots use native software Canvas; API31+ sampled blur/refraction and physical haptics have not been verified on a physical device. Backdrop blur is an approximation; predictive reveal uses a snapshot of the previous page, not a live underlying page during the gesture. No claim of pixel-perfect or original RLottie equivalence.

The APK v2 signature was verified and the certificate matches1.8.0, permitting in-place upgrade with the same package and retained data.
