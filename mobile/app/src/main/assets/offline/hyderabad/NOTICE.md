# Central Hyderabad offline prototype pack

Coverage: west 78.35, south 17.30, east 78.60, north 17.55 (WGS84 degrees).
This is not all Hyderabad, Telangana or India. Tile zooms 10–14; higher display
zooms overzoom existing geometry and do not add detail. No routing graph.

Data: © OpenStreetMap contributors, ODbL 1.0.
https://www.openstreetmap.org/copyright
https://opendatacommons.org/licenses/odbl/1-0/

Vector tiles provided by OpenFreeMap, OpenMapTiles schema.
https://openfreemap.org/
https://openmaptiles.org/
OpenMapTiles attribution: © OpenMapTiles. Schema/code licensing and notices:
https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md
OpenFreeMap project and third-party notices:
https://github.com/hyperknot/openfreemap/blob/main/LICENSE.md

Fonts: Noto Sans Regular via OpenFreeMap glyph service; SIL Open Font License 1.1.
https://github.com/notofonts/latin-greek-cyrillic/blob/main/OFL.txt
This first style uses English/Latin labels; no claim of complete Telugu/local-language coverage.

The Kotlin style is original project presentation code; no Google Maps content
or branding is copied. No public OSM raster tiles were scraped. The app reads
only this local pack, without API keys, Internet permission or network fallback.
The manifest records the source snapshot, resource sizes and SHA-256 checksums.
Generation is explicit: python mobile/tools/build_hyderabad_pack.py --download
