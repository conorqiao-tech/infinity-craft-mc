// Browser regression for the built Field Guide. See website/README.md for setup.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

async function checkCounterpoint(page, origin) {
  await page.addInitScript(() => {
    window.noteStarts = [];
    // Distinguish two reads within one audio render quantum.
    let reads = 0;
    const clock = Object.getOwnPropertyDescriptor(BaseAudioContext.prototype, 'currentTime').get;
    Object.defineProperty(BaseAudioContext.prototype, 'currentTime', {
      get() { return clock.call(this) + ++reads / 1000000; }
    });
    const start = OscillatorNode.prototype.start;
    OscillatorNode.prototype.start = function(time) {
      window.noteStarts.push({ time, pitch: this.frequency.value });
      return start.apply(this, arguments);
    };
  });
  await page.goto(new URL('/field-guide/#pairs', origin).href);
  const panel = page.getByRole('tabpanel', { name:'Postcard Pairs' });
  const answers = panel.locator('[data-a]');
  const lead = panel.locator('[data-k]');
  const cards = panel.locator('[data-card]');
  const status = panel.locator('#cpStatus');
  const sound = panel.locator('#sound2');
  const notes = () => page.evaluate(() => window.noteStarts);
  const tap = button => button.press('Enter');

  assert.equal(await answers.first().getAttribute('aria-disabled'), 'true');
  await sound.uncheck();
  await tap(cards.nth(0));
  await panel.getByRole('checkbox', { name:/swap/i }).check();
  await tap(cards.nth(1));
  await tap(panel.getByRole('button', { name:/close the circuit/i }));
  await tap(cards.nth(2));
  assert.equal(await answers.first().getAttribute('aria-disabled'), 'true');

  const pitches = [1046.5, 329.63, 1568];
  const partials = [3, 4, 4];
  for (const audible of [false, true]) {
    await sound.setChecked(audible);
    for (const direction of [1, -1]) {
      for (const opening of [0, 1, 2]) {
        await panel.getByRole('checkbox', {name:/reverse route/i}).setChecked(direction < 0);
        const route = [0, 1, 2, 3].map(beat => (opening + beat * direction + 6) % 3);
        for (const corner of route) await tap(lead.nth(corner));
        for (const [beat, corner] of route.entries()) {
          const answer = (corner + direction + 3) % 3;
          const before = (await notes()).length;
          await tap(answers.nth(corner));
          assert.equal((await notes()).length, before);
          assert.match(await status.innerText(), new RegExp('needs corner ' + (answer + 1)));
          await tap(answers.nth(answer));
          const sounded = (await notes()).slice(before);
          const voices = beat === 3 ? [0, 1, 2] : [corner, answer];
          assert.equal(sounded.length, audible ? voices.reduce((n, v) => n + partials[v], 0) : 0);
          if (audible) {
            assert.equal(new Set(sounded.map(note => note.time)).size, 1, 'All voices share one start time');
            for (const voice of voices) assert(sounded.some(note => Math.abs(note.pitch - pitches[voice]) < 2));
          }
        }
        assert.match(await status.innerText(), /Counterpoint complete/);
      }
    }
  }
}

async function main() {
  const origin = process.argv[2];
  if (!origin) throw new Error('Pass the origin of the built InfinityHub website.');
  const browser = await chromium.launch({executablePath: process.env.CHROME_BIN});
  try {
    await checkCounterpoint(await browser.newPage(), origin);
    console.log('Passed all six Counterpoint routes with sound on and off.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
