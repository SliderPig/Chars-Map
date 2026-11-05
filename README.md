# {C} Char's Map

A minimap and world map for Forge 1.20.1, built to be as fast as it can be on a weak PC. Only your own PC needs it.

- **Minimap** in a corner: rounded square or circle, north up or turning with you, zoom keys, biome + coords, mob dots, facing arrow.
- **World map** on `M`: drag to move, wheel to zoom towards the mouse. Right-click to add a waypoint.
- **Waypoints**: `B` adds one, list on the world map, your last 3 deaths are kept, Overworld/Nether places show across the two (x/8), a thin beam in the world.
- **Fill from generated chunks**: on your own world the map reads the save files directly, nearest first, and fills in places you haven't been to since joining. Automatic within a set reach (Settings), "Fill now" for the whole dimension, `/charmap fill [all]`.
- **Teammates**: with the teams mod installed, heads + names on both maps, pinned to the minimap's edge when out of range.
- **Nether**: shows the ground below the roof, or a slice around your height (Settings). **Caves**: underground, the map changes to a slice around your height by itself.
- `/charmap fill [all] | stop | clear | debug`. All keys can be rebound.

Settings live in `config/charmap.properties`, the map itself in `charmap/<world>/<dimension>/`.

## How it stays fast

- **Nothing on the main thread but copying.** A chunk is copied in a few microseconds per section (only the sections that matter, found from its height map); colouring, tile updates and disk happen on one low-priority thread that alone owns the tiles, so there are no locks to wait on. Up to 0.5 ms a frame goes on copying, 1 ms on uploads.
- **A pyramid of same-sized tiles.** Every texture is 512x512. Level 0 is one region; each level up covers twice as far at half the detail. Any zoom needs about the same 10-25 textures, and they come from a fixed reused pool (64 MB by default), so there's nothing to allocate while you play.
- **The map never waits.** A tile not ready shows its coarser parent stretched over it until it arrives. Uploads go a strip at a time. The minimap shows what's on disk before any chunk has arrived.
- **No extra rendering.** Circles and rounded corners are the map's own triangles cut to the outline, so no stencil, no framebuffer and no shader that could fight another mod.
- **One colour per block, worked out once**: the average of the top face's texture, tinted by biome (grass, leaves, water), hills shaded when drawn against the pixels north and west, also across tile edges, so regions join without seams.
- **Unchanged chunks cost almost nothing**: each chunk has a fingerprint; the same fingerprint stops right there. The fill skips any chunk not saved since the map last had it.
- **The fill reads save files itself**, with its own small reader: no chunks are loaded through the server, no game code runs. It eases off when frames drop.
- **Never crashes the game**: any trouble is logged once, the map carries on.

## A server can help (optional)

With this mod on the server too, "Fill now" on a server asks it for small summaries of its saved chunks (block names + heights, about a few hundred bytes a chunk), sent at 128 KB/s at most. It shows parts nobody has walked, so only ops can ask unless `config/charmap-server.properties` says `allowEveryone=true`. Without the mod on the server, you just get what you've seen.

## Building and testing

`gradlew build` makes `build/libs/charmap-1.0.jar`. `gradlew test` runs the pure logic. `gradlew build -Psmoke -x test` then `python tools/run_real_client.py` starts the real game in a throwaway folder and walks through everything, leaving screenshots in `run-real/screenshots`; add `reopen` to reopen the same world, `fps=on|off` to measure only. Rebuild without `-Psmoke` before installing.
