// Browser regression for the built Field Guide. See website/README.md for setup.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

function assertVoices(sounded, voices, audible) {
  const pitches = [1046.5, 329.63, 1568];
  const partials = [3, 4, 4];
  assert.equal(sounded.length, audible ? voices.reduce((n, v) => n + partials[v], 0) : 0);
  if (!audible) return;
  assert.equal(new Set(sounded.map(note => note.time)).size, 1, 'All voices share one start time');
  for (const voice of voices) assert(sounded.some(note => Math.abs(note.pitch - pitches[voice]) < 2));
}

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
  const stamp = panel.getByRole('button', { name: /^Stamp seal$/i });
  const next = panel.getByRole('button', { name: /^Play next beat$/i });
  const restart = panel.getByRole('button', { name: /^Play from start$/i });
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

  assert(await stamp.isDisabled());
  assert(await next.isDisabled());
  for (const audible of [false, true]) {
    await sound.setChecked(audible);
    for (const direction of [1, -1]) {
      for (const opening of [0, 1, 2]) {
        await panel.getByRole('checkbox', {name:/reverse route/i}).setChecked(direction < 0);
        const route = [0, 1, 2, 3].map(beat => (opening + beat * direction + 6) % 3);
        for (const corner of route) await tap(lead.nth(corner));
        assert(await stamp.isDisabled());
        for (const [beat, corner] of route.entries()) {
          const answer = (corner + direction + 3) % 3;
          const before = (await notes()).length;
          await tap(answers.nth(corner));
          assert.equal((await notes()).length, before);
          assert.match(await status.innerText(), new RegExp('needs corner ' + (answer + 1)));
          await tap(answers.nth(answer));
          const sounded = (await notes()).slice(before);
          const voices = beat === 3 ? [0, 1, 2] : [corner, answer];
          assertVoices(sounded, voices, audible);
        }
        assert.match(await status.innerText(), /Counterpoint complete/);
        await tap(answers.nth((opening + direction + 3) % 3));
        const beforeStamp = (await notes()).length;
        await tap(stamp);
        assert.equal((await notes()).length, beforeStamp, 'Stamping is silent');
        for (const [beat, corner] of route.entries()) {
          const before = (await notes()).length;
          await tap(next);
          const voices = beat === 3 ? [0, 1, 2] : [corner, (corner + direction + 3) % 3];
          assertVoices((await notes()).slice(before), voices, audible);
        }
      }
    }
  }
  // The last completed route starts at corner 3 and moves backward.
  await panel.getByRole('checkbox', {name:/swap/i}).check();
  await tap(cards.nth(2));
  await tap(cards.nth(1));
  await tap(panel.getByRole('button', {name:/close the circuit/i}));
  await panel.getByRole('checkbox', {name:/swap/i}).check();
  await tap(cards.nth(0));
  assert(await stamp.isDisabled());
  await page.reload();
  assert(await stamp.isDisabled());
  assert.equal((await notes()).length, 0, 'Restoring a seal does not play it');
  await tap(restart);
  assertVoices(await notes(), [2, 1], true);
  let before = (await notes()).length;
  await tap(next);
  assertVoices((await notes()).slice(before), [1, 0], true);
  before = (await notes()).length;
  await tap(restart);
  assertVoices((await notes()).slice(before), [2, 1], true);

  async function restore(seal) {
    await page.evaluate(snapshot => {
      const key = 'infinitycraft.field-guide.v1';
      const state = JSON.parse(localStorage.getItem(key));
      state.seal = snapshot;
      localStorage.setItem(key, JSON.stringify(state));
    }, seal);
    await page.reload();
    assert.equal((await notes()).length, 0);
  }
  const repeated = {ver:1, notes:['kite','kite','kite'], open:0, dir:false};
  for (const invalid of [{...repeated, ver:2}, {...repeated, open:3}, {...repeated, notes:['kite','unknown','kite']}]) {
    await restore(invalid);
    assert(await next.isDisabled(), 'Unsupported seal data stays silent and locked');
  }
  await restore(repeated);
  await tap(next);
  const repeatedNotes = await notes();
  assert.equal(repeatedNotes.length, 7, 'Repeated note keeps both chime and harp timbres');
  assert(repeatedNotes.some(n => Math.abs(n.pitch - 1046.5) < 2));
  assert(repeatedNotes.some(n => Math.abs(n.pitch - 261.63) < 2));


}

async function main() {
  const origin = process.argv[2];
  if (!origin) throw new Error('Pass the origin of the built InfinityHub website.');
  const browser = await chromium.launch({executablePath: process.env.CHROME_BIN});
  try {
    await checkCounterpoint(await browser.newPage(), origin);
    console.log('Passed all six Counterpoint routes and seal scores with sound on and off, plus saved playback after circuit replacement and reload.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
