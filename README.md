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

Default Fabric version

Before: **Without mods**

After: **With LCull**

![lcull1](https://cdn.modrinth.com/data/WiGIrt51/images/fd29f588b51b1b4591150c1f115ecb599a6a6bde.png)
#

Fabric with mods: Sodium, Entity Culling, Fabric API

Before: **Without LCull**

After: **With LCull**

![lcull2](https://cdn.modrinth.com/data/WiGIrt51/images/4e80e23e81d36def722a919465a80f9177e34f2f.png)
