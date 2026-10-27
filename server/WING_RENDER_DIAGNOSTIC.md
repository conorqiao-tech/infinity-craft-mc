# Explore.51 r2 temporary wing rendering diagnostic

This is a **diagnostic**, not a claimed wing appearance fix. The owner reports
that installed explore.51 r1 still shows ordinary gray elytra after a visible
pack download. Native packet tests retain the custom item markers and player
property pairs, but do not execute the iPad's renderer or Molang expressions.

The diagnostic uses pack revision **2.13.51002**, on a copy of the normal shield
grip candidate. It adds small temporary model badges and English labels to
distinguish pack loading, attachable loading, property access and visibility.
It intentionally marks **ordinary as well as custom, worn or held elytra** for
Bedrock players receiving the forced pack. Those temporary appearance changes
require explicit owner approval. No accepted PNG, original model, gameplay
power, Java mod, world or terrain is changed. The normal wing selectors remain
in place; a badge is evidence about a rendering stage, not a repaired chestplate.

Each badge has a fixed position, described by the helper's generated legend.
In model coordinates, the three rows are **A / T / H**, **P / B / S**, and
**V / E / Z**. A client camera can mirror the grid or letters; record the visible
shapes and positions rather than assuming a tested screen orientation. The
block letters sit above and behind the torso. The helmet
control shows only the A letter in its same position; wear it separately when
checking the independent control so it is not confused with the elytra's A.
The always-on Infinity helmet control uses the same badge geometry, texture and
render pattern. Its presence is a separate resource-loading check. English UI
labels are supplemental only: custom item names and other languages can mask
them. Do not interpret missing wing markers if the independent control is also
absent. A literal-false negative-control badge must never appear; if it does,
the diagnostic readout is invalid.

The stages are:

| Badge | Evidence when visible |
| --- | --- |
| A | Elytra attachable's extra render pass loaded; independent of custom flags. |
| T | The diagnostic constant assigned in `pre_animation` reaches the renderer. |
| H | The owning entity exposes the winged-chest property. |
| P | The owning entity reads that property as true without the slot guard. |
| B | The original plain slot-to-bone query returns `body`. |
| S | The current owning-entity slot-to-bone query returns `body`. |
| V | The existing stored combined-chest flag reaches the renderer. |
| E | The current final body visibility condition permits the combined pass. |
| Z | Literal false: must be absent, otherwise the readout is invalid. |

H, P, B and S are evaluated into separate diagnostic variables in the
attachable's `pre_animation`, matching the production selector's context. The
badge controller reads those results; they never depend on the stored V flag.
T is a separate constant assignment, and E uses the production renderer's final
visibility condition. The generated legend records the expressions and stages.
A missing badge can also
reflect a geometry, material or client-context problem, so record observations
before assigning a cause. Inspect in third person in-world first, then the
inventory character separately; those contexts can differ. Keep both hands
empty to avoid mistaking held-elytra badges for the equipped item. Check the
Infinity helmet control first, with the chest slot empty, then remove the helmet
and equip Infinity Chestplate for the wing readout. Inspect before and after unequipping
and re-equipping Infinity Chestplate, and capture the front and back. Do not
claim a glide result without actually checking it.

The diagnostic also contains the normal shield sprite fallback so its hand
placement can be compared in the same approved test window. The paired
**2.13.51003 cleanup** removes all diagnostic content and restores the exact
reviewed normal shield pack. Both archive hashes and the exact transition
hashes are recorded in `PAIR.json` and each `PATCH.json`. Always use the paired
cleanup, not an edited diagnostic pack or a lower revision.

No live change is authorized by building these files. Obtain separate owner
approval for the temporary diagnostic and its cleanup; save/stop, verify a fresh
complete backup, apply the exact resource overlay and restart for each approved
transition. Preserve worlds, player/helper state, private settings, the mod and
terrain. Remove the diagnostic after the device comparison using the approved
cleanup. A failed device result remains a failed candidate, regardless of
headless checks.
