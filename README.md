# ChunkScan

Paper/Spigot plugin that reads the `InhabitedTime` of every generated chunk straight from the region files
(no chunks are loaded) and finds the smallest radius around a point that contains every chunk players have
spent more than a given time in. Meant for cleaning up worlds that were pre-generated with
[Chunky](https://github.com/pop4959/Chunky) before trimming them.

## Usage

```
/chunkscan <minTime> [chunky | spawn [world] | origin [world] | here | <x> <z> [world]] [--export]
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

- `--export` (anywhere after `minTime`): also write a CSV with every generated chunk, see below.

Tab completion suggests the options.

Permission: `chunkscan.use` (op by default).

The scan uses every CPU core and is built for maintenance windows: run it when nobody is online.
The world is saved first so loaded chunks are included, and the center that was used is printed at the start.

### Chunky during a scan

So that no region files change while they are read, a scan:

1. disables the plugins listed in `suspend-plugins` (default: `ChunkyAutoPause`), which would otherwise continue
   Chunky on their own,
2. pauses every running Chunky task (`pause-chunky: true`),

and after the scan continues those tasks and enables the plugins again.

`plugins/ChunkScan/config.yml`:

```yaml
pause-chunky: true
suspend-plugins:
  - ChunkyAutoPause
```

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
- `<world>-<time>-visited.csv` – every chunk with InhabitedTime > 0, sorted by time
- `<world>-<time>-chunks.csv` – only with `--export`: one row per generated chunk (see below)

### Full export (`--export`)

| Column | Meaning |
|---|---|
| `chunkX`, `chunkZ` | chunk coordinates |
| `minBlockX`, `minBlockZ` | block coordinates of the chunk's north-west corner |
| `region` | region file the chunk is stored in |
| `inhabitedTicks` | InhabitedTime in ticks (20 ticks = 1 second) |
| `inhabitedTime` | the same as `H:MM:SS` |
| `aboveThreshold` | `true` if InhabitedTime is above `minTime` |
| `distanceToCenter` | blocks from the scan center to the chunk's center |
| `status` | generation status; `full` means completely generated, anything else is a partially generated border chunk |
| `dataVersion` | data version of the Minecraft version that last saved the chunk ([list](https://minecraft.wiki/w/Data_version)) |
| `lastUpdateTick` | world game time (ticks) when the chunk was last saved |
| `compression` | `zlib` (default), `gzip`, `none`, `lz4`; `+external` if the chunk is too big for the region file and lives in a `.mcc` file |
| `sizeOnDiskBytes` | disk space the chunk takes (allocated 4 KiB sectors, plus the `.mcc` file) |
| `error` | why a chunk couldn't be read, empty otherwise |

Rows are grouped by region file, not sorted. The export reads every chunk completely, so it is slower than a
normal scan, and it gets big: roughly 100 bytes per chunk (a 10,000 block radius is about 1.2 million chunks,
~120 MB). Excel only opens the first 1,048,576 rows; LibreOffice, DuckDB or Python handle more.

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
The same version is also uploaded to Modrinth once the repository variable `MODRINTH_PROJECT_ID` is set
(token in the secret `MODRINTH_PAT`). The changelog is the list of commits since the previous release.

## License

[MIT](LICENSE)
