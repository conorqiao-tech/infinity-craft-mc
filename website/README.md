# Infinity Armor Hub website source

This is the static guide used at [Infinity Armor Hub](https://infinity-armor-hub.vercel.app/). It explains the shared Java + Bedrock server, game modes, rewards, and joining. Vercel hosts this website; the host Mac runs the Minecraft world and its private control panel.

Edit `catalogue.json`, `site-template.html`, `dist/style.css`, and `dist/app.js`. Regenerate the HTML with:

```sh
python3 build_site.py
python3 -m http.server 8765 --bind 127.0.0.1 --directory dist
```

Open `http://127.0.0.1:8765/` locally to preview. The build writes `dist/index.html`, which is intentionally untracked here. Release ZIPs and guide downloads must be produced and checked separately before deploying the complete `dist` folder to the `qiaokans-projects/infinityhub` Vercel project; `dist/downloads` is excluded from Git because it contains generated archives. The artwork in `dist/assets` comes from the supplied Infinity Armor textures. Do not put current tunnel addresses, owner-panel links, or private settings into the static site.

The installable guide uses the same origin and starts at the hub homepage. Postcard Pairs, Postcard Circuit, Circuit Conductor, the two-voice Counterpoint challenge, Chord Seal playback, Seal Cipher, Cipher Refrain, Refrain Cadence, Cadence Pennant, Pennant Fanfare, and Pennant Chorus at `/field-guide/#pairs` and Coda Studio at `/field-guide/#studio` come from the private Claude Field Guide Artifact. Its standalone export lives in `dist/field-guide/index.html`. The build versions the guide worker from the offline shell’s bytes. Generated release downloads stay outside the offline cache.

The Workspace link opens `/workspace/` on the same site. Visitors supply their own OpenAI API key and can submit one selected UTF-8 text file per request. The app sends it to GPT-6 Luna through `/api/workspace`, holds no local or server history, and lets the visitor review and download a proposed copy. The key, prompt and selected text are processed by Vercel and OpenAI. Deploy the website project root with `dist/`, `api/`, `package.json` and `vercel.json` together so the API is available. Run `npm test` from `website/` for the mocked API checks.

To check Counterpoint against a running build, make Playwright available to Node and run `node website/qa/counterpoint.cjs <site-origin>` from the repository root. Set `CHROME_BIN` to use an installed Chrome executable. The test plays all six routes by keyboard with sound on and off and checks the timing and pitches of the live and stamped phrases. It also checks seal playback after a circuit change and reload, repeated notes, and rejection of unknown saved formats.

Use the same browser setup with `node website/qa/seal-cipher.cjs <site-origin>` to check the missing-instrument game, including repeated pitches, duplicate guesses and seal playback independence. The same check performs the three missing voices in both directions and verifies the solo pitches, silent hints and final triad. After each refrain it tries every solo knot, rejects both pairs containing the soloist, checks the simultaneous answering duet, and plays the optional full chord.

The Field Guide keeps the last pennant the player chooses after a correct cadence. Both Guide and Postcard Pairs show it after reload. A new performance replaces the saved design only when the player keeps it. A kept pennant plays a two-beat fanfare even with no seal. The browser check also compares the solo and answering pitches, tab switching, restart, mute and failed saves. Pennant Chorus collects one performed solo per instrument until all three sound together. Its score lasts for the session. The browser check verifies a chord assembled from different pennants and replacement of a collected pitch.
