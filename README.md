# ChunkScan

Paper/Spigot plugin that reads the `InhabitedTime` of every generated chunk straight from the region files
(no chunks are loaded) and finds the smallest radius around a point that contains every chunk players have
spent more than a given time in. Meant for cleaning up worlds that were pre-generated with
[Chunky](https://github.com/pop4959/Chunky) before trimming them.

## Usage

```
/chunkscan <minTime> [chunky | spawn [world] | origin [world] | here | <x> <z> [world]]
```

- `minTime`: `30s`, `5m`, `1h`, `600t` (ticks); a plain number means seconds. Chunks need an InhabitedTime
  strictly above this to count as visited.
- Center:
  - `chunky` – Chunky's current selection (`/chunky world`, `/chunky center`)
  - `spawn [world]` – the world spawn
  - `origin [world]` – 0, 0
  - `here` – your own position
  - `<x> <z> [world]` – a specific point
  - nothing – same as `chunky`, falling back to your position (or the spawn from the console) if Chunky isn't installed

  Without a world, the world you're in is used (the main world from the console).

Tab completion suggests the options.

Permission: `chunkscan.use` (op by default).

The scan uses every CPU core and is built for maintenance windows: run it when nobody is online.
The world is saved first so loaded chunks are included.

## Output

In chat:
- distribution of InhabitedTime over all generated chunks
- smallest circle and square radius (in blocks) that fully contain every visited chunk, the chunk that defines it,
  and how many chunks would be outside
- the `/chunky` command(s) to set that radius

In `plugins/ChunkScan/`:
- `<world>-<time>.png` – heatmap, one pixel per chunk, north up. Dark gray: generated but never visited,
  blue: visited below the threshold, yellow → red: above the threshold, green circle: suggested radius,
  white cross: center
- `<world>-<time>.csv` – every chunk with InhabitedTime > 0, sorted by time

## Notes

- Supports gzip, zlib, uncompressed and external (`.mcc`) chunks, pre-1.18 and current chunk formats.
  LZ4-compressed chunks (`region-file-compression=lz4`) are counted but skipped.
- The radius covers the whole farthest chunk; Chunky's trim keeps a chunk when its center is inside the
  shape, so the suggested radius is slightly conservative.

## Building

```
mvn package
```

The jar ends up in `target/`. GitHub Actions builds it on every push to `master` (or manually via "Run workflow");
download it from the run's artifacts. Pushes to `dev` are not built.

When the version in `pom.xml` has no release yet, the master build also publishes a GitHub release
(`v<version>`) with the jar attached. Bump the version to publish the next one.
