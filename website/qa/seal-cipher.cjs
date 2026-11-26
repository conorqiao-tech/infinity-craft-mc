// Run against a built Field Guide with Playwright and an installed Chromium.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const names = ['Chime', 'Harp', 'Bell'];
const frequencies = {kite:1046.50, moon:1318.51, ember:1567.98};
const partials = [3, 4, 4];

async function verify(page, origin) {
  await page.addInitScript(() => {
    window.cipherNotes = [];
    const start = OscillatorNode.prototype.start;
    OscillatorNode.prototype.start = function(time) {
      window.cipherNotes.push({time, pitch:this.frequency.value});
      return start.apply(this, arguments);
    };
  });
  await page.goto(new URL('/field-guide/#pairs', origin).href);
  const panel = page.getByRole('tabpanel', {name:'Postcard Pairs'});
  const cipher = panel.getByRole('region', {name:/Seal Cipher/i});
  const status = cipher.getByRole('status');
  const button = label => cipher.getByRole('button', {name:label});
  const refrain = panel.getByRole('region', {name:/Cipher Refrain/i});
  const direction = backward => refrain.getByRole('button', {name:backward ? /backward refrain/i : /forward refrain/i});
  const instrument = v => refrain.getByRole('button', {name:new RegExp('^Refrain: '+names[v]+'$', 'i')});
  const hint = refrain.getByRole('button', {name:/reveal next voice/i});
  const feedback = refrain.getByRole('status');
  const start = button(/start cipher/i);
  const replay = button(/replay clue/i);
  const tap = control => control.press('Enter');
  const heard = () => page.evaluate(() => window.cipherNotes);
  const assertAudio = (notes, seal, voices, audible) => {
    assert.equal(notes.length, audible ? voices.reduce((sum,v) => sum + partials[v], 0) : 0);
    if (!audible) return;
    assert.equal(new Set(notes.map(n => n.time)).size, 1, 'All clue voices share one start time');
    for (const v of voices) {
      const hz = frequencies[seal.notes[v]] / (v === 1 ? 4 : 1);
      assert(notes.some(n => Math.abs(n.pitch - hz) < 2), 'The recorded pitch belongs to the correct instrument');
    }
  };
  assert(await start.isDisabled(), 'A seal must be stamped first');
  for (const reverse of [false, true]) for (const opening of [0, 1, 2]) {
    const audible = opening !== 1;
    const seal = {ver:1, notes:opening === 2 ? ['moon','moon','moon'] : ['kite','moon','ember'], open:opening, dir:reverse};
    await page.evaluate(({seal,audible}) => {
      const key = 'infinitycraft.field-guide.v1';
      const state = JSON.parse(localStorage.getItem(key) || '{}');
      localStorage.setItem(key, JSON.stringify({...state, sound:audible, seal}));
    }, {seal,audible});
    await page.reload();
    assert.deepEqual(await heard(), [], 'No audio on reload');
    await tap(panel.getByRole('button', {name:'Play next beat'}));
    let before = (await heard()).length;
    assert(await direction(false).isDisabled(), 'A new session must solve its cipher');
    await tap(start);
    const step = reverse ? -1 : 1;
    for (let round = 0; round < 3; round++) {
      const lead = (opening + round * step + 6) % 3;
      const answer = (lead + step + 3) % 3;
      const missing = 3 - lead - answer;
      assertAudio((await heard()).slice(before), seal, [lead,answer], audible);
      before = (await heard()).length;
      if (round > 0) {
        // This was the correct answer to the preceding round.
        await tap(button(new RegExp('^' + names[answer] + '$','i')));
        assert.equal((await heard()).length, before);
        assert.equal(await cipher.locator('#cxMarks .lit').count(), round);
      }
      await tap(button(new RegExp('^' + names[lead] + '$','i')));
      assert.equal((await heard()).length, before, 'Wrong guess is silent');
      await tap(replay);
      assertAudio((await heard()).slice(before), seal, [lead,answer], audible);
      before = (await heard()).length;
      await tap(button(new RegExp('^' + names[missing] + '$','i')));
    }
    assertAudio((await heard()).slice(before), seal, [0,1,2], audible);
    assert.match(await status.innerText(), /3\s*\/\s*3|solved|complete/i);
    const sequence = [0,1,2].map(i => (opening + (i+2)*step + 6) % 3);
    await tap(direction(true));
    await tap(instrument(sequence[2]));
    assert.match(await feedback.innerText(), /knot 2 is next/i);
    await tap(direction(false));
    assert.match(await feedback.innerText(), /knot 1 is waiting/i);
    for (const backward of [false,true]) {
      before = (await heard()).length;
      await tap(direction(backward));
      const voices = backward ? sequence.slice().reverse() : sequence;
      assert.equal((await heard()).length, before, 'Starting a refrain is silent');
      for (const [i,voice] of voices.entries()) {
        await tap(instrument((voice+1)%3));
        await tap(hint);
        await tap(hint);
        assert.match(await feedback.innerText(), new RegExp(names[voice], 'i'));
        assert.equal((await heard()).length, before, 'Miss and repeated hints are silent');
        await tap(instrument(voice));
        const packets = await heard();
        assertAudio(packets.slice(before), seal, i === 2 ? [0,1,2] : [voice], audible);
        before = packets.length;
      }
      assert.match(await feedback.innerText(), /refrain complete/i);
      assert.deepEqual((await refrain.getByRole('listitem').allTextContents()).map(t => t.match(/done, (Chime|Harp|Bell)/)[1]), voices.map(v => names[v]));
      assert(await direction(!backward).isEnabled(), 'Either direction can be replayed');
    }
    await tap(button(/start again/i));
    assert(await direction(false).isDisabled() && await hint.isDisabled(), 'Restarting the cipher locks the refrain');
    await tap(panel.getByRole('button', {name:'Play next beat'}));
    assert.match(await panel.locator('#sStatus').innerText(), /beat 2 of 4/i, 'Puzzle does not advance ordinary playback');
    await page.reload();
    assert(await start.isEnabled() && await replay.isDisabled(), 'Fresh visit keeps the seal and clears the puzzle');
  }
}

(async () => {
  const origin = process.argv[2];
  if (!origin) throw new Error('Pass the origin of a built InfinityHub website.');
  const browser = await chromium.launch({executablePath:process.env.CHROME_BIN});
  try {
    await verify(await browser.newPage(), origin);
    console.log('PASS: all six Seal Cipher routes and twelve Refrains, including equal pitches, muted rounds, repeated hints, solo and triad audio, session reset and independent seal playback.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
