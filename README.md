# {C} Char's Map

A minimap and world map for Forge 1.20.1, built to be as fast as it can be on a weak PC. Your own PC needs it; a server can have it too, and then everyone with the mod gets a filled-in map.

- **Minimap** in a corner: rounded square or circle, north up or turning with you, zoom keys, biome + coords, facing arrow. Mobs and players show as real faces (or dots, or nothing: Settings).
- **World map** on `M`: drag to move, wheel to zoom towards the mouse. Right-click to add a waypoint.
- **3D view**: the "3D view" button on the world map shows the same map as real blocks and mountains. Drag to move, right-drag to turn, wheel to zoom, WASD to fly. "Flat map" goes back.
- **Waypoints**: `B` adds one, list on the world map, your last 3 deaths are kept, Overworld/Nether places show across the two (x/8), a thin beam in the world.
- **Fill from generated chunks**: on your own world the map reads the save files directly, nearest first, and fills in places you haven't been to since joining. Automatic within a set reach (Settings), "Fill now" for the whole dimension, `/charmap fill [all]`.
- **Teammates**: with the teams mod installed, heads + names on both maps, pinned to the minimap's edge when out of range.
- **Nether**: shows the ground below the roof, or a slice around your height (Settings). **Caves**: underground, the map changes to a slice around your height by itself. A surface map leaves out mobs in the caves below it.
- `/charmap fill [all] | stop | clear | faces | debug`. All keys can be rebound.

Settings live in `config/charmap.properties`, the map itself in `charmap/<world>/<dimension>/`.

## How it stays fast

The minimap comes first:

- **One quad a frame.** The whole corner map (map, ring, faces, text) is one ready-made picture. A frame that has nothing new just draws that one quad. It's only remade when something on it changes: you moved half a pixel, a mob in view moved (10 times a second at most), new map data arrived (5 times a second at most). While you look around, only the arrow is redrawn.
- **The map part slides.** Under that sits a second picture, twice as wide as the map, built from the tiles. Moving and turning just slide over it; it's rebuilt only when you get near its edge, so flying costs almost nothing.
- **It gives way while you fly**: no second looks at chunks, no faces being pictured, no fill starting. Up to 0.3 ms a frame goes on copying chunks, 1 ms on uploads, in 32-row strips.

Everything underneath:

- **Nothing on the main thread but copying.** A chunk is copied in microseconds (only the sections that matter, found from its height map); colouring, tile updates and disk happen on one thread that alone owns the tiles, so there are no locks to wait on.
- **A pyramid of same-sized tiles.** Every texture is 512x512. Level 0 is one region; each level up covers twice as far at half the detail. Any zoom needs about the same 10-25 textures, from a fixed reused pool (64 MB by default). A tile not ready shows its coarser parent stretched over it until it arrives.
- **No tricks that fight other mods.** Circles and rounded corners are the map's own triangles cut to the outline. One small mixin stops the world being drawn behind the full-screen map (the map fills the window, so that time is better spent on the map); if another mod gets in the way it just does nothing.
- **One colour per block, worked out once**: the average of the top face's texture, tinted by biome (grass, leaves, water), hills shaded when drawn against the pixels north and west, also across tile edges, so regions join without seams.
- **Unchanged chunks cost almost nothing**: each chunk has a fingerprint; the same fingerprint stops right there. The fill reads a region file's table first and skips the file if nothing's newer than what the map has.
- **The fill reads save files itself**, with its own small reader: no chunks are loaded through the server, no game code runs. It eases off when frames drop.
- **Faces** are pictured once from a fresh copy of the mob, kept in `charmap/icons`, and all share one texture, so a crowd is one draw.
- **The 3D view** is patches of columns that get wider with distance, built on the map's own thread and only sent to the graphics card on the main thread. It only exists while the 3D view is open.
- **Never crashes the game**: any trouble is logged once, the map carries on.

## On a server

Put the same jar on the server. Players with the mod then get a summary of every generated chunk (block names and heights, about 2 KB a chunk) automatically, nearest first, so their map fills in without walking anywhere. It remembers per player how far it got, so a big world carries on over several sessions (in `<world>/charmap-progress`). The server reads its save files on its own low-priority thread, never loads a chunk and never touches the main thread for it. Players without the mod notice nothing.

`config/charmap-server.properties`: `enabled` (default true), `opsOnly` (default false: it does show the map of parts nobody has walked, so set this if that matters to you), `kilobytesPerSecond` per player (default 128), `playersAtOnce` (default 3).

## Building and testing

`gradlew build` makes `build/libs/charmap-1.0.jar`. `gradlew test` runs the pure logic. `gradlew build -Psmoke` then `python tools/run_real_client.py` starts the real game in a throwaway folder and walks through everything, leaving screenshots in `run-real/screenshots`; add `reopen` to reopen the same world, `fps=on|off` to measure only, `join=host:port` to join a server. `python tools/run_server_test.py --from <folder with Arclight's libraries>` does the real server and client together. `gradlew runGameTestServer -Psmoke` checks the mod on a real dedicated server reading a real save. Rebuild without `-Psmoke` before installing.
