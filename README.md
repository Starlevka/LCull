![LCull icon](https://cdn.modrinth.com/data/cached_images/2e7022e9a5f1d124e506e56f3f2075e831c623c5_0.webp)

<sub><i>The project code was created with help of Artificial Intelligence.</i></sub>

An open source mod with cursed, performant Frustum logic for heavy culling scenes. **More FPS, but has some side effects**.

## Advantages
- Culling entities that off-screen:
    - Helps in different heavy scenes which **increases FPS**. (like in CounterMine 2)
- Accelerates work by **x2** with **Entity Culling**, **Sodium** mods, cuz they're professionals here yea.

## Weaknesses
- Instant dissappearing entities that off-screen.
- Useless for other scenes:
    - Low amounts of entities
    - Entities behind the walls.
- Not effective in singleplayer.
- **Increases CPU load.**

## Benchmarks

Default 1.21.11 Fabric version

Before: **Without mods**

After: **With LCull**

![lcull1](https://cdn.modrinth.com/data/WiGIrt51/images/fd29f588b51b1b4591150c1f115ecb599a6a6bde.png)


1.21.11 Fabric with mods: Sodium, Entity Culling, Fabric API

Before: **Without LCull**

After: **With LCull**

![lcull2](https://cdn.modrinth.com/data/WiGIrt51/images/4e80e23e81d36def722a919465a80f9177e34f2f.png)

## Why I made this mod?

Minecraft keeps every loaded entity in the render loop even when it is behind your back or far outside the screen, so the GPU and CPU pay full price for things you will never see. In entity-heavy scenes, like RPG-modded servers, minigame hubs or CounterMine maps WITH UP TO 10K ENTITIES (or just a much amount of Display entities), that overhead turns into a real frame-rate tax. Sodium and Entity Culling already do excellent work on the block and entity side, but I wanted a small, focused frustum path tuned specifically for thousands of entities on screen at once, and a clean place to experiment with the rendering pipeline. LCull grows out of the ideas in Lomka (my earlier work that proved tick and refresh culling help a lot) and narrows the scope to one job: stop drawing entities that are not in the view frustum.

## How the LCull's Frustum checks works?

Every frame the game already builds a Frustum for the active camera. LCull asks that frustum whether an entity's axis-aligned bounding box lives inside the visible volume, using the same cube-in-frustum test the renderer uses for chunks. When the whole box is outside, the entity is skipped during rendering and never reaches the draw call. The tricky part is stability: the frustum shifts a little whenever you move, change FOV or resize the window, and a naive check would make entities flicker at the screen edges. To avoid that LCull keeps a tiny per-entity cache of the last cull decision and only flips it when the camera context stays stable for a few ticks. The cache key is a quantized camera position plus the view direction plus the entity position, so a small camera jitter does not invalidate the result. This hysteresis gives smooth culling without re-evaluating every entity every frame.

## What the Minecraft and LCull problem?

Minecraft does apply its own frustum to chunks and block entities, but the per-entity render loop still iterates and submits everything that is loaded, which is exactly why off-screen mobs cost as much as on-screen ones. LCull removes that off-screen cost, yet it cannot remove entities hidden behind walls, because that needs occlusion culling and a visibility graph, a much harder problem than a frustum test. The mod also does nothing useful when there are only a few entities, or in singleplayer where the simulation, not rendering, is the bottleneck. Because culling is decided on the render thread, entities can pop out the instant they leave the view; that is the intended trade for the FPS win, and it trades a little extra CPU work for the frustum evaluation. There is also a narrow compatibility edge: a mod that assumes every entity is "rendered" each frame could behave oddly, which is why LCull stays an opt-in, tunable tweak rather than a silent override.

## Links
- [Source](https://github.com/Starlevka/LCull)
- [Issue tracker](https://github.com/Starlevka/LCull/issues)
