# Android Panadapter and Waterfall v4

TCI float I/Q enters the existing native `shackcq_panadapter` implementation through a bounded JNI bridge. Android does not create a second spectrum engine.

The TCI surface provides selected/dual-receiver layouts, FIT and manual levels, peak hold/reset, four independent color palettes, VFO/passband/band-plan overlays, 30 fps publication, and a true scrolling bounded waterfall history.

Markers remain selection/review controls. They do not tune without an explicit action. Debug Lab I/Q is labelled fake and cannot establish received-RF evidence.
