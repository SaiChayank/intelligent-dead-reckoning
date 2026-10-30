# Map matching on real drives (N7)

The earlier map-matching evidence was synthetic (simulated vehicles on the Hyderabad graph). This evaluation uses REAL IO-VNBD drives
(Coventry area, UK) and a REAL OpenStreetMap road graph, with forced GNSS outages, scored against the VBOX reference.

Rebuild the inputs (nothing here is committed: OSM data is ODbL and large). The Overpass query is a union of one box per area where a drive
was recorded; the exact query used is saved next to the extract as `data/raw/osm/uk_highways.query.txt`, and the boxes go into
`data/processed/roadgraph/uk-v1/coverage.json` (`{"boxes_west_south_east_north": [[w,s,e,n], ...]}`).

```bash
curl -A "idr-research/1.0" -o data/raw/osm/uk_highways.osm.json --data-urlencode "data@data/raw/osm/uk_highways.query.txt" https://overpass-api.de/api/interpreter
python tools/build_road_graph.py --input data/raw/osm/uk_highways.osm.json --out-dir data/processed/roadgraph/uk-v1 --id uk-road-graph-v1 --bounds=-1.94,52.0,-0.70,53.08 --mirror https://overpass-api.de/api/interpreter
python tools/iovnbd_outage_eval.py run --input vehicle --arms ekf_nhc_v3_heldout_vel_nhcout_map,ekf_nhc_v3_heldout_vel_nhcout_mapx
python tools/n7_map_comparison.py
```

Only drives lying wholly inside the graph's box get map arms. The results and their reading are in `reports/gap_closure_log.md` (N7) and
`reports/n7_map_comparison.json`. The OSM extract is a 2026-10-30 snapshot; the IO-VNBD drives are older, so road changes since are a
(small) source of mismatch.
