# EARAM Tabs — AlphaTab Web Viewer

This folder adds a browser-based Guitar Pro viewer to the EARAM-Tabs repository.

## Features

- Open `.gp`, `.gp3`, `.gp4`, `.gp5`, and `.gpx`
- Standard notation above tablature
- Play / Pause / Stop
- AlphaTab playback cursor
- Playback position and progress
- Song title, track/instrument and tempo
- Built-in demo score
- Responsive layout

## Run

Requirements: Node.js 20.19+.

```bash
cd web
npm install
npm run dev
```

For a production build:

```bash
npm run build
npm run preview
```

AlphaTab is pinned to the latest stable 1.8.4 release.
