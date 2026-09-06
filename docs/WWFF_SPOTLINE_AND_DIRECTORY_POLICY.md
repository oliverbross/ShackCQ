# WWFF Spotline and directory policy

The [official WWFF Spotline guidance](https://wwff.co/spotline/) documents public integration JSON for recent spots, active agendas and all agendas, recommends refresh no faster than 30 seconds, and documents the DXSpider-compatible port 7300 service. ShackCQ uses bounded HTTPS, conditional validators, schema validation, atomic last-good files and no posting/API key for the public JSON.

Live states are `AVAILABLE`, `STALE`, `OFFLINE_CACHE`, `EMPTY`, and `ERROR` in operator UI. They are independent of directory availability. Valid live spots/agendas may provide a partial cache of discovered references.

The [official WWFF Directory](https://wwff.co/directory/) prohibits reproduction or storage without prior permission. ShackCQ does not scrape or bundle it. Full reference coverage therefore requires a documented API/key or an operator-authorised import; otherwise the app offers exact official external handoff and labels the directory state accordingly.
