# Infinity Armor Hub website source

This is the static guide used at [Infinity Armor Hub](https://infinity-armor-hub.shio-coder.chatgpt.site/). It explains the shared Java + Bedrock server, game modes, rewards, and joining. The website does not run the Minecraft world or expose the host's private control panel.

Edit `catalogue.json`, `site-template.html`, `dist/style.css`, and `dist/app.js`. Regenerate the HTML with:

```sh
python3 build_site.py
python3 -m http.server 8765 --bind 127.0.0.1 --directory dist
```

Open `http://127.0.0.1:8765/` locally to preview. The build writes `dist/index.html`, which is intentionally untracked here. Release ZIPs and guide downloads must be produced and checked separately before publishing; `dist/downloads` is excluded from Git because it contains generated archives. The artwork in `dist/assets` comes from the supplied Infinity Armor textures. Do not put current tunnel addresses, owner-panel links, or private settings into the static site.
