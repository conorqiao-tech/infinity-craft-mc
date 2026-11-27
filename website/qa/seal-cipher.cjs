// Run against a built Field Guide with Playwright and an installed Chromium.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const names = ['Chime', 'Harp', 'Bell'];
const frequencies = {kite:1046.50, moon:1318.51, ember:1567.98};
const harmonics = [[1,2,3], [1,2,3,4], [1,2,2.76,5.4]];

async function verify(page, origin) {
  await page.addInitScript(() => {
    window.cipherNotes = [];
    const write = Storage.prototype.setItem;
    Storage.prototype.setItem = function(key,value) {
      if (window.blockPennantWrite) throw new DOMException('Storage full', 'QuotaExceededError');
      return write.call(this,key,value);
    };
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
    const pitches = audible ? voices.flatMap(v => {
      const fundamental = frequencies[seal.notes[v]] / (v === 1 ? 4 : 1);
      return harmonics[v].map(multiple => fundamental * multiple);
    }) : [];
    assert.equal(notes.length, pitches.length);
    if (pitches.length === 0) return;
    assert.equal(new Set(notes.map(n => n.time)).size, 1, 'All voices start together');
    const ascending = (a,b) => a-b;
    const played = notes.map(n => n.pitch).sort(ascending);
    pitches.sort(ascending).forEach((pitch,i) => assert(Math.abs(pitch-played[i]) < 2, 'Correct instrument and pitch'));
  };
  const cadence = panel.getByRole('region', {name:/Refrain Cadence/i});
  const cadenceButton = name => cadence.getByRole('button', {name});
  const soloKnot = i => cadenceButton(new RegExp('knot '+(i+1), 'i'));
  const answerPair = first => cadenceButton(new RegExp(names[first]+' \\+ '+names[(first+1)%3], 'i'));
  const fullChord = cadenceButton(/play full chord/i);
  const weaveAgain = cadenceButton(/weave again/i);
  const keep = page.locator('#pnKeep');
  const saved = () => page.evaluate(() => JSON.parse(localStorage.getItem('infinitycraft.field-guide.v1')).pennant);
  const dyes = {kite:'#169C9C', moon:'#8932B8', ember:'#F9801D'};
  async function keepPennant(seal,solo,testFailure) {
    const previous = await saved();
    const originalCaption = await page.locator('#pnSavedCap').innerText();
    if (testFailure) {
      await tap(page.locator('#ffMain_p'));
      assert.equal(await page.locator('#ffMain_p').innerText(), 'Play answer');
      await page.evaluate(() => {window.blockPennantWrite=true;});
      await tap(keep);
      assert.match(await page.locator('#pnStatus').innerText(), /try again/i);
      assert(await keep.isEnabled());
      assert.equal(await page.locator('#ffMain_p').innerText(), 'Play answer', 'A failed keep preserves the pending answer');
      assert.deepEqual(await saved(), previous);
      assert.equal(await page.locator('#pnSavedCap').innerText(), originalCaption);
      await page.evaluate(() => {window.blockPennantWrite=false;});
    }
    const audioCount = (await heard()).length;
    await tap(keep);
    assert.equal((await heard()).length, audioCount, 'Keeping a pennant is silent');
    assert(await keep.isDisabled(), 'Each finished cadence can be kept once');
    assert.equal(await page.locator('#ffMain_p').innerText(), 'Play call');
    assert(await page.locator('#ffRestart_p').isDisabled(), 'An explicit keep starts the score over');
    assert.deepEqual(await saved(), {ver:1, solo:names[solo].toLowerCase(), notes:seal.notes});
    const design = page.locator('#pnSaved svg g');
    const stripes = design.locator('rect[shape-rendering]');
    for (const side of [0,1]) {
      assert.equal(await stripes.nth(side).getAttribute('fill'), dyes[seal.notes[(solo+side+1)%3]]);
    }
    const center = design.locator(['rect[x="9.5"]','line[stroke-width="5"]','circle'][solo]);
    assert.equal(await center.getAttribute(solo === 1 ? 'stroke' : 'fill'), dyes[seal.notes[solo]]);
    const caption = await page.locator('#pnSavedCap').innerText();
    assert.match(caption,new RegExp('Solo '+names[solo]));
    assert.equal(await page.locator('#gPenCap').textContent(), caption);
  }
  async function performCadences(voices, seal, audible) {
    for (const [knot,solo] of voices.entries()) {
      assert(await keep.isDisabled(), 'An unfinished cadence cannot replace a keepsake');
      const previousPennant = await saved();
      assert(await fullChord.isDisabled(), 'Full chord unlocks after the answering duet');
      let before = (await heard()).length;
      await tap(soloKnot(knot));
      assertAudio((await heard()).slice(before), seal, [solo], audible);
      before = (await heard()).length;
      const first = (solo+1)%3, second = (solo+2)%3;
      for (const wrong of [solo,second]) {
        await tap(answerPair(wrong));
        assert.equal((await heard()).length, before, 'A pair containing the soloist stays silent');
        assert(await fullChord.isDisabled(), 'A wrong pair cannot finish the cadence');
      }
      await tap(answerPair(first));
      assertAudio((await heard()).slice(before), seal, [first,second], audible);
      assert.deepEqual(await saved(), previousPennant, 'Completing a new cadence leaves the old pennant on display');
      await keepPennant(seal,solo,knot === 1);
      before = (await heard()).length;
      await tap(fullChord);
      assertAudio((await heard()).slice(before), seal, [0,1,2], audible);
      before = (await heard()).length;
      await tap(weaveAgain);
      assert.equal((await heard()).length, before, 'Choosing another solo starts silently');
    }
  }
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
      assert(await soloKnot(0).isDisabled(), 'A fresh refrain locks its cadence');
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
      await performCadences(voices, seal, audible);
      assert(await direction(!backward).isEnabled(), 'Either direction can be replayed');
    }
    await tap(button(/start again/i));
    assert(await soloKnot(0).isDisabled(), 'Starting a fresh cipher locks its cadence');
    assert(await direction(false).isDisabled() && await hint.isDisabled(), 'Restarting the cipher locks the refrain');
    await tap(panel.getByRole('button', {name:'Play next beat'}));
    assert.match(await panel.locator('#sStatus').innerText(), /beat 2 of 4/i, 'Puzzle does not advance ordinary playback');
    const kept = await saved();
    await page.reload();
    assert.deepEqual(await saved(), kept);
    assert.match(await page.locator('#gPenCap').textContent(), new RegExp('Solo '+kept.solo,'i'));
    assert(await keep.isDisabled());
    assert(await soloKnot(0).isDisabled(), 'Reload clears the cadence');
    assert(await start.isEnabled() && await replay.isDisabled(), 'Fresh visit keeps the seal and clears the puzzle');
  }
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  // A gift can be the only item in a returning player's guide.
  const guideTab = page.getByRole('tab', {name:'Guide', exact:true});
  const pairsTab = page.getByRole('tab', {name:'Postcard Pairs', exact:true});
  for (const notes of [['kite','moon','ember'], ['moon','moon','moon']]) {
    for (const [solo, role] of names.entries()) {
      const pennant = {ver:1, solo:role.toLowerCase(), notes};
      await page.evaluate(pennant => localStorage.setItem('infinitycraft.field-guide.v1',
        JSON.stringify({sound:true, pennant})), pennant);
      await page.reload();
      assert.deepEqual(await heard(), [], 'Loading a keepsake is silent');
      assert(await start.isDisabled(), 'This listener has no seal');
      const left = (solo+1)%3, right = (solo+2)%3;
      async function fanfare(id, voices, audible=true) {
        const before = (await heard()).length;
        await tap(page.locator(id));
        assertAudio((await heard()).slice(before), pennant, voices, audible);
        assert.equal(await page.locator('#ffStatus_g').textContent(), await page.locator('#ffStatus_p').textContent());
      }
      await tap(guideTab);
      assert(await page.locator('#ffRestart_g').isDisabled());
      await fanfare('#ffMain_g', [solo]);
      await tap(pairsTab);
      assert.equal(await page.locator('#ffMain_p').innerText(), 'Play answer');
      await fanfare('#ffRestart_p', [solo]);
      assert.match(await page.locator('#ffStatus_p').innerText(), /Call restarted/);
      await fanfare('#ffMain_p', [left,right]);
      assert.match(await page.locator('#ffStatus_p').innerText(),
        new RegExp(names[left]+'.*left stripe.*'+names[right]+'.*right stripe','i'));
      await page.locator('#sound2').uncheck();
      await fanfare('#ffMain_p', [solo], false);
      await tap(guideTab);
      await fanfare('#ffMain_g', [left,right], false);
      assert.match(await page.locator('#ffStatus_g').innerText(), /Silent.*answer/i);
      await fanfare('#ffRestart_g', [solo], false);
      await page.reload();
      assert.equal(await page.locator('#ffMain_g').innerText(), 'Play call');
      assert(await page.locator('#ffRestart_g').isDisabled());
      assert.deepEqual(await heard(), []);
      await tap(pairsTab);
    }
  }
  const collect = suffix => page.locator('#'+(suffix === 'g' ? 'guide' : 'pairs'))
    .getByRole('button', {name:'Collect solo', exact:true});
  // Keep a souvenir from a different score, then earn the other parts from this seal.
  for (const mode of ['mixed', 'replace', 'muted']) {
    const audible = mode !== 'muted';
    const seal = {ver:1, notes:['kite','moon','ember'], open:1, dir:true};
    const gift = {ver:1, solo:'chime', notes:['ember','kite','kite']};
    await page.evaluate(state => localStorage.setItem('infinitycraft.field-guide.v1',JSON.stringify(state)),
      {pennant:gift, seal, sound:audible});
    await page.reload();
    const play = async () => {
      await tap(page.locator('#ffMain_p'));
      assert(await collect('p').isDisabled(), 'A call alone cannot be collected');
      await tap(page.locator('#ffMain_p'));
      assert(await collect('p').isEnabled(), 'An answered solo can be collected');
    };
    async function take(count, finalNotes) {
      await tap(guideTab);
      const before = (await heard()).length;
      await tap(collect('g'));
      assertAudio((await heard()).slice(before), {notes:finalNotes || seal.notes},
        finalNotes ? [0,1,2] : [], audible);
      const legend = await page.locator('#chList_g').innerText();
      assert.match(legend, finalNotes ? /Trio complete/ : new RegExp(count+' of 3'));
      if (finalNotes) {
        const labels = {kite:'Kite', moon:'Half Moon', ember:'Ember Peaks'};
        for (const [role,id] of finalNotes.entries()) {
          assert.match(legend, new RegExp(labels[id]+' '+names[role], 'i'));
        }
      }
      assert.equal(await page.locator('#chFig_g').getAttribute('aria-label'), await page.locator('#chFig_p').getAttribute('aria-label'));
      assert(await collect('g').isDisabled(), 'Only one collection per answer');
      await tap(pairsTab);
      assert(await collect('p').isDisabled(), 'Both views consumed the same answer');
    }
    const weave = async solo => {
      await tap(soloKnot(solo));
      await tap(answerPair((solo+1)%3));
      await tap(keep);
      assert(await collect('p').isDisabled(), 'A newly kept design needs its own performance');
    };
    await play();
    await tap(page.locator('#ffRestart_p'));
    assert(await collect('p').isDisabled(), 'Restarting disarms collection');
    await tap(page.locator('#ffMain_p'));
    await take(1);
    await tap(start);
    for (const v of ['Bell','Harp','Chime']) await tap(button(v));
    await tap(direction(true));
    for (const i of [0,1,2]) await tap(instrument(i));
    await weave(1);
    await play();
    await take(2);
    await tap(weaveAgain);
    if (mode === 'replace') {
      await weave(0);
      await play();
      await take(2); // Replace the first pitch after harp has joined. Chime keeps its place.
      await tap(weaveAgain);
    }
    await tap(soloKnot(2));
    await tap(answerPair(0));
    await play(); // The still-kept harp has a collectible answer while a bell is waiting to be kept.
    await page.evaluate(() => {window.blockPennantWrite=true;});
    await tap(keep);
    assert(await collect('p').isEnabled(), 'A failed keep preserves the collectible performance');
    await page.evaluate(() => {window.blockPennantWrite=false;});
    await tap(keep);
    assert(await collect('p').isDisabled());
    await play();
    const trio = {notes: mode === 'replace' ? seal.notes : ['ember','moon','ember']};
    await take(3, trio.notes);
    for (const backward of [false, true]) {
      const order = backward ? [2,1,0] : [0,1,2];
      for (let beat = 0; beat < 4; beat++) {
        const side = beat % 2 === 0 ? 'p' : 'g';
        await tap(side === 'p' ? pairsTab : guideTab);
        const id = beat === 0 ? backward ? 'btBack' : 'btFwd' : 'btNext';
        const before = (await heard()).length;
        await tap(page.locator('#'+id+'_'+side));
        assertAudio((await heard()).slice(before), trio, beat === 3 ? [0,1,2] : [order[beat]], audible);
        const score = page.locator('#btScore_'+side);
        const current = await score.locator('[aria-current="step"]').innerText();
        assert(current.includes('Beat '+(beat+1)));
        assert(current.includes(beat === 3 ? 'Full chord' : names[order[beat]]+' solo'));
        assert.equal(await page.locator('#btScore_p').getAttribute('aria-label'), await page.locator('#btScore_g').getAttribute('aria-label'));
        assert.equal(await page.locator('#btFwd_'+side).isDisabled(), beat < 3);
        assert.equal(await page.locator('#btBack_'+side).isDisabled(), beat < 3);
        assert.equal(await page.locator('#btNext_'+side).isEnabled(), beat < 3);
      }
    }
    await tap(pairsTab);
    await tap(page.locator('#btFwd_p'));
    await play();
    assert(await page.locator('#btNext_p').isEnabled(), 'Performing another fanfare preserves the current phrase');
    await take(1); // A new bell is one slot into the next chorus and remains silent.
    assert(await page.locator('#btNext_p').isDisabled(), 'A successful new collection clears the unfinished phrase');
    assert(await page.locator('#btBack_p').isDisabled());
    await page.reload();
    assert.deepEqual(await heard(), []);
    assert(await collect('p').isDisabled());
    assert.equal(await page.locator('#btScore_p').getAttribute('aria-label'), 'Baton score locked');
    assert.deepEqual(await saved(), {ver:1, solo:'bell', notes:seal.notes});
  }
  const testSeal = {ver:1, notes:['kite','moon','ember'], open:1, dir:true};
  for (const bad of [null,123,'obsolete',[],{ver:2,solo:'harp',notes:testSeal.notes},{ver:1,solo:'other',notes:testSeal.notes},{ver:1,solo:'bell',notes:['kite','unknown','moon']}]) {
    const root = bad && typeof bad==='object' && !Array.isArray(bad) ? {seal:testSeal,pennant:bad} : bad;
    await page.evaluate(value=>localStorage.setItem('infinitycraft.field-guide.v1',JSON.stringify(value)),root);
    await page.reload();
    assert.equal(await page.locator('#gPen').getAttribute('aria-label'),'No pennant kept yet');
    for (const view of ['g','p']) {
      assert(await page.locator('#ffMain_'+view).isDisabled());
      assert(await page.locator('#ffRestart_'+view).isDisabled());
    }
    if (root?.seal) assert(await start.isEnabled(), 'Invalid souvenir cannot disable a valid seal');
  }
  assert.deepEqual(errors,[]);
}

(async () => {
  const origin = process.argv[2];
  if (!origin) throw new Error('Pass the origin of a built InfinityHub website.');
  const browser = await chromium.launch({executablePath:process.env.CHROME_BIN});
  try {
    await verify(await browser.newPage(), origin);
    console.log('PASS: all six Seal Cipher routes, twelve Refrains and 36 Cadences; unison and mute, correct solo and simultaneous answering pair, optional triad and independent seal playback; all 36 pennants, visible layers, explicit replacement, failed-save retry, reload and invalid saves. Kept-pennant fanfares passed for all three solos, distinct and repeated charts, cross-tab play and restart, muted and audible, with no seal. Mixed and replacement choruses passed with three collected pitches, one audio start, silent partials, failed-save recovery and fresh trios. Chorus Baton passed forward and backward solo playback, simultaneous final chords, mixed and replaced pitches, shared direction and beat across tabs, and clearing on a new collection.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
