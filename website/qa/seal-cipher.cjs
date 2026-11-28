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
  const oldPrism = {ver:1, voices:['bell','harp','chime'].map(role => ({role, note:'kite'}))};
  const storedPrism = () => page.evaluate(() => JSON.parse(localStorage.getItem('infinitycraft.field-guide.v1')).prism);
  const cut = side => page.locator('#prCut_'+side);
  // Keep a souvenir from a different score, then earn the other parts from this seal.
  for (const mode of ['mixed', 'replace', 'muted']) {
    const audible = mode !== 'muted';
    const seal = {ver:1, notes:['kite','moon','ember'], open:1, dir:true};
    const gift = {ver:1, solo:'chime', notes:['ember','kite','kite']};
    await page.evaluate(state => localStorage.setItem('infinitycraft.field-guide.v1',JSON.stringify(state)),
      {pennant:gift, seal, sound:audible, prism:oldPrism});
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
    const expectedPrism = {ver:1, voices:names.map((role,i) => ({role:role.toLowerCase(),note:trio.notes[i]}))};
    async function playFacet(side, facet, notes, instruments) {
      const before = (await heard()).length;
      await tap(page.locator('#pr'+facet+'_'+side));
      assertAudio((await heard()).slice(before), {notes}, instruments, audible);
      const face = facet === 'L' ? 'left' : 'right';
      const caption = await page.locator('#prFig_'+side).getAttribute('aria-label');
      assert.match(caption, new RegExp(face+' facet is hatched as last played'));
      assert.equal(await page.locator('#prFig_g').getAttribute('aria-label'), await page.locator('#prFig_p').getAttribute('aria-label'));
      for (const panel of ['p','g']) {
        const svg = page.locator('#prFig_'+panel+' svg');
        const pattern = await svg.locator('pattern').getAttribute('id');
        assert.equal(await page.locator('[id="'+pattern+'"]').count(), 1, 'Hatch belongs to this panel');
        assert.equal(await svg.locator('polygon[fill="url(#'+pattern+')"]').count(), 1);
      }
    }
    const ring = side => page.locator(side === 'p' ? '#pairs' : '#guide')
      .getByRole('button', {name:'Ring the loop', exact:true});
    const loopStatus = side => page.locator('#lpStatus_'+side).textContent();
    const inlay = side => page.locator('#inSet_'+side);
    const choice = (side, field, value) => page.locator('input[name="in'+field+'_'+side+'"][value="'+value+'"]');
    assert.match(await loopStatus('p'), /New loop score: first .*chime.*last .*bell/i);
    const firstBackward = mode === 'replace';
    for (const backward of [firstBackward, firstBackward, !firstBackward]) {
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
        const bothComplete = backward !== firstBackward && beat === 3;
        assert.equal(await ring(side).isEnabled(), bothComplete, 'Each direction must reach its closing chord');
        assert(await inlay(side).isDisabled(), 'Completing a loop does not open an inlay until it rings');
      }
    }
    await tap(pairsTab);
    await tap(page.locator('#btFwd_p'));
    const beatOne = await page.locator('#btScore_p').getAttribute('aria-label');
    for (const side of ['g','p']) {
      await tap(side === 'g' ? guideTab : pairsTab);
      const before = (await heard()).length;
      await tap(ring(side));
      assert(await cut(side).isDisabled(), 'Ringing clears any uncut opportunity');
      assertAudio((await heard()).slice(before), trio, [0,2], audible);
      assert.equal(await page.locator('#btScore_'+side).getAttribute('aria-label'), beatOne);
      assert.match(await page.locator('#inFig_'+side).getAttribute('aria-label'), /diamond not set.*attempt open/i);
      let beforeGuess = (await heard()).length;
      await choice(side,'Role','bell').check();
      await choice(side,'Note','moon').check();
      await tap(inlay(side));
      assert.match(await page.locator('#inStatus_'+side).innerText(), /right chart.*wrong instrument/i);
      await choice(side,'Role','harp').check();
      await choice(side,'Note','kite').check();
      await tap(inlay(side));
      assert.match(await page.locator('#inStatus_'+side).innerText(), /right instrument.*wrong chart/i);
      assert.equal((await heard()).length, beforeGuess, 'Wrong guesses are silent');
      await choice(side,'Note','moon').check();
      const other = side === 'g' ? 'p' : 'g';
      await tap(other === 'p' ? pairsTab : guideTab);
      assert(await choice(other,'Role','harp').isChecked());
      assert(await choice(other,'Note','moon').isChecked());
      await tap(inlay(other));
      assertAudio((await heard()).slice(beforeGuess), trio, [0,1,2], audible);
      assert.match(await page.locator('#inFig_'+other).getAttribute('aria-label'), /diamond earned: Half Moon harp/i);
      assert.equal(await page.locator('#inFig_'+side).getAttribute('aria-label'), await page.locator('#inFig_'+other).getAttribute('aria-label'));
      assert(await inlay(other).isDisabled());
      beforeGuess = (await heard()).length;
      await tap(inlay(other));
      assert.equal((await heard()).length, beforeGuess, 'One triad per ring');
      assert(await cut(other).isEnabled());
      if (side === 'p') {
        // The old prism's route is Bell, Harp, Chime. Its pitches are all Kite.
        const unison = ['kite','kite','kite'];
        const crossingText = () => page.locator('#crStatus_'+other).innerText();
        async function answerCrossing(view, facet, instruments) {
          const before = (await heard()).length;
          await tap(page.locator('#pr'+facet+'_'+view));
          assertAudio((await heard()).slice(before), {notes:unison}, instruments, audible);
          assert.equal(await page.locator('#crStatus_g').textContent(), await page.locator('#crStatus_p').textContent());
          assert.equal(await page.locator('#crFig_g').getAttribute('aria-label'), await page.locator('#crFig_p').getAttribute('aria-label'));
        }
        const post = (view, name) => page.locator('#lw'+name+'_'+view);
        const figure = view => page.locator('#lwFig_'+view).getAttribute('aria-label');
        async function visitPost(view, name, instruments) {
          const before = (await heard()).length;
          await tap(post(view, name));
          assertAudio((await heard()).slice(before), {notes:unison}, instruments, audible);
          assert.equal(await figure('p'), await figure('g'));
          assert.equal(await page.locator('#lwStatus_p').textContent(), await page.locator('#lwStatus_g').textContent());
        }
        assert(await post(other, 'Arch').isDisabled());
        const viewpoint = (view, number) => page.locator('#svPick'+number+'_'+view);
        const beacon = (view, control) => page.locator('#mb'+control+'_'+view);
        const relayStop = (view, stop) => page.locator('#brPick'+stop+'_'+view);
        const ribbon = (view, control) => page.locator('#rr'+control+'_'+view);
        const lens = (view, control) => page.locator('#sl'+control+'_'+view);
        const trace = (view, id) => page.locator('#st'+id+'_'+view);
        async function stamp(view, column, voices) {
          const before = (await heard()).length;
          await tap(trace(view, 'Pick'+column));
          assertAudio((await heard()).slice(before), {notes:unison}, voices, audible);
          assert.equal(await trace('g','Status').textContent(), await trace('p','Status').textContent());
          assert.equal(await trace('g','Fig').getAttribute('aria-label'), await trace('p','Fig').getAttribute('aria-label'));
        }
        async function project(view, tint, voices) {
          const before = (await heard()).length;
          await tap(lens(view,tint));
          assertAudio((await heard()).slice(before), {notes:unison}, voices, audible);
          assert.equal(await lens('p','Status').textContent(), await lens('g','Status').textContent());
          assert.equal(await lens('p','Fig').getAttribute('aria-label'), await lens('g','Fig').getAttribute('aria-label'));
        }
        async function returnBeat(view, control, voices) {
          const before = (await heard()).length;
          await tap(ribbon(view, control));
          assertAudio((await heard()).slice(before), {notes:unison}, voices, audible);
          assert.equal(await ribbon('p','Status').textContent(), await ribbon('g','Status').textContent());
          assert.equal(await ribbon('p','Fig').getAttribute('aria-label'), await ribbon('g','Fig').getAttribute('aria-label'));
        }
        async function relay(view, stop, instruments) {
          const before = (await heard()).length;
          await tap(relayStop(view, stop));
          assertAudio((await heard()).slice(before), {notes:unison}, instruments, audible);
          assert.equal(await page.locator('#brStatus_p').textContent(), await page.locator('#brStatus_g').textContent());
          assert.equal(await page.locator('#brFig_p').getAttribute('aria-label'), await page.locator('#brFig_g').getAttribute('aria-label'));
        }
        async function light(view, instruments, status) {
          const before = (await heard()).length;
          await tap(beacon(view, 'Light'));
          assertAudio((await heard()).slice(before), {notes:unison}, instruments, audible);
          assert.match(await beacon(view, 'Status').innerText(), status);
          assert.equal(await beacon('p', 'Status').textContent(), await beacon('g', 'Status').textContent());
          assert.equal(await beacon('p', 'Fig').getAttribute('aria-label'), await beacon('g', 'Fig').getAttribute('aria-label'));
        }
        const surveyMap = view => page.locator('#svFig_'+view).getAttribute('aria-label');
        async function survey(view, number, instruments) {
          const before = (await heard()).length;
          await tap(viewpoint(view, number));
          assertAudio((await heard()).slice(before), {notes:unison}, instruments, audible);
          assert.equal(await surveyMap('p'), await surveyMap('g'));
          assert.equal(await page.locator('#svStatus_p').textContent(), await page.locator('#svStatus_g').textContent());
        }
        assert(await viewpoint(other, 1).isDisabled());
        const beforeStart = (await heard()).length;
        await tap(page.locator('#crReverse_'+other));
        assert.match(await crossingText(), /chime.*bell/i);
        assert.equal((await heard()).length, beforeStart);
        await answerCrossing(other, 'L', []);
        await answerCrossing(other, 'R', [1,0]);
        await page.evaluate(() => {window.blockPennantWrite=true;});
        await tap(cut(other));
        await page.evaluate(() => {window.blockPennantWrite=false;});
        assert(await cut(other).isEnabled());
        assert.match(await page.locator('#prStatus_'+other).innerText(), /previous prism is unchanged.*try again/i);
        assert.deepEqual(await storedPrism(), oldPrism);
        await tap(pairsTab);
        await answerCrossing(side, 'R', []);
        await answerCrossing(side, 'L', [0,1,2]);
        assert.match(await page.locator('#crFig_'+side).getAttribute('aria-label'), /complet|both.*lit/i);
        await visitPost(side, 'Arch', [0]);
        const firstStop = await figure(side);
        await visitPost(side, 'Arch', []);
        assert.equal(await figure(side), firstStop);
        await tap(guideTab);
        await page.evaluate(() => {window.blockPennantWrite=true;});
        await tap(cut(other));
        await page.evaluate(() => {window.blockPennantWrite=false;});
        assert.equal(await figure(other), firstStop, 'Failed prism replacement preserves the current walk');
        await visitPost(other, 'Pool', [1]);
        const twoStops = await figure(other);
        await visitPost(other, 'Spire', []); // 8 blocks from Pool, 16 from Arch
        assert.equal(await figure(other), twoStops);
        await tap(pairsTab);
        await visitPost(side, 'Grove', [2]);
        assert.match(await figure(side), /star|constellation/i);
        const completed = await figure(side);
        assert(await viewpoint(side, 1).isEnabled());
        await survey(side, 3, []); // Arch to Pool is shorter than either slope to Grove.
        assert(await beacon(side, 'Light').isDisabled());
        await visitPost(side, 'Ridge', []);
        assert.equal(await figure(side), completed);
        await tap(guideTab);
        await playFacet(other, 'L', unison, [2,1]);
        await playFacet(other, 'R', unison, [1,0]);
        const tied = mode === 'replace' ? 1 : 2;
        await survey(other, tied, tied === 1 ? [1,2] : [0,2]);
        const success = await page.locator('#svStatus_'+other).innerText();
        assert.match(success, /tied for longest.*span endpoints/i);
        assert.equal(/silent/i.test(success), !audible, 'Survey text reflects the sound setting');
        assert(await viewpoint(other, 1).isDisabled());
        await survey(other, tied, []);
        assert.equal(await figure(other), completed);
        await light(other, [], /choose both X and Z/i);
        assert(await relayStop(other, 1).isDisabled());
        const midX = tied === 1 ? '6' : '2';
        await beacon(other, 'X').selectOption(tied === 1 ? '2' : '6');
        await tap(pairsTab);
        await light(side, [], /choose a Z/i);
        await beacon(side, 'Z').selectOption('4');
        await light(side, [], /adjust X/i); // The other tied span is a different target.
        await playFacet(side, 'R', unison, [1,0]);
        await tap(guideTab);
        assert.equal(await beacon(other, 'Z').inputValue(), '4');
        await beacon(other, 'X').selectOption(midX);
        await page.evaluate(() => {window.blockPennantWrite=true;});
        await tap(cut(other));
        await page.evaluate(() => {window.blockPennantWrite=false;});
        assert.equal(await beacon(other, 'X').inputValue(), midX);
        await light(other, [tied-1], /beacon lit.*observer solo/i);
        assert.equal(/silent/i.test(await beacon(other, 'Status').innerText()), !audible);
        assert.match(await beacon(other, 'Fig').getAttribute('aria-label'), /rays from both endpoints and a halo/i);
        await light(other, [], /already lit/i);
        // Both glass coordinates on these tied survey routes are exact midpoints.
        const opposite = tied === 1 ? 2 : 1;
        const lead = mode === 'muted' ? 3 : opposite;
        const finish = lead === 3 ? opposite : 3;
        await relay(other, tied, []);
        await relay(other, lead, [lead-1]);
        assert(await ribbon(other,'Start').isDisabled());
        await relay(other, lead, []);
        await playFacet(other, 'L', unison, [2,1]);
        await page.evaluate(() => {window.blockPennantWrite=true;});
        await tap(cut(other));
        await page.evaluate(() => {window.blockPennantWrite=false;});
        await tap(pairsTab);
        await relay(side, finish, [0,1,2]);
        assert.match(await page.locator('#brFig_p').getAttribute('aria-label'), /infinity|figure.eight/i);
        await relay(side, finish, []);
        await returnBeat(side, 'Start', [finish-1]);
        await returnBeat(side, 'Start', []);
        await returnBeat(side, 'Pick'+lead, []);
        await tap(guideTab);
        await playFacet(other, 'L', unison, [2,1]);
        await page.evaluate(() => {window.blockPennantWrite=true;});
        await tap(cut(other));
        await page.evaluate(() => {window.blockPennantWrite=false;});
        await returnBeat(other, 'Pick'+tied, [tied-1]);
        await returnBeat(other, 'Pick'+finish, []);
        assert(await trace(other,'Pick1').isDisabled());
        assert(await lens(other,'Kite').isDisabled());
        await returnBeat(other, 'Pick'+lead, [lead-1,finish-1]);
        assert.match(await ribbon(other,'Fig').getAttribute('aria-label'), /star/i);
        assert.equal(/^silent/i.test(await ribbon(other,'Status').innerText()), !audible);
        await returnBeat(other, 'Pick'+lead, []);
        assert(await trace(other,'Pick2').isDisabled());
        await project(other, 'Moon', []);
        await tap(pairsTab);
        await project(side, 'Kite', [tied-1]);
        assert.match(await lens(side,'Fig').getAttribute('aria-label'), /010.*101.*010/);
        const cells = await lens(side,'Fig').locator('[data-cell]').evaluateAll(nodes =>
          nodes.map(node => node.getAttribute('data-lit')).join(''));
        assert.equal(cells,'010101010');
        await stamp(side, 1, []);
        await stamp(side, 2, [1]);
        assert.equal(await trace(side,'Fig').locator('[data-column="2"] [data-trace-mark]').count(), 1);
        await tap(guideTab);
        await playFacet(other,'L',unison,[2,1]);
        await page.evaluate(() => {window.blockPennantWrite=true;});
        await tap(cut(other));
        await page.evaluate(() => {window.blockPennantWrite=false;});
        await project(other,'Ember',[]);
        await project(other,'Kite',[]);
        await stamp(other, 3, []);
        await stamp(other, 2, []);
        assert.match(await trace(other,'Fig').getAttribute('aria-label'), /Double underline on column 2/);
        assert.match(await lens(other,'Fig').getAttribute('aria-label'), /010.*101.*010/);
        await tap(page.locator('#crStart_'+other));
        assert(await trace(other,'Pick1').isDisabled());
        assert(await lens(other,'Kite').isDisabled());
        assert(await ribbon(other,'Start').isDisabled());
        assert(await relayStop(other, 2).isDisabled());
        assert(await beacon(other, 'Light').isDisabled());
        assert.equal(await beacon(other, 'X').inputValue(), '');
        await answerCrossing(other, 'L', [2,1]);
        assert(await post(other, 'Pool').isDisabled());
        await answerCrossing(other, 'R', [0,1,2]);
        assert(await viewpoint(other, 1).isDisabled());
        if (mode === 'muted') {
          await visitPost(other, 'Spire', [2]); // Replace a prism during an unfinished walk.
        } else {
          await visitPost(other, 'Arch', [2]);
          await visitPost(other, 'Cairn', [1]);
          await visitPost(other, 'Pool', [0]);
          await survey(other, 2, []); // 64 loses to 65 squared blocks.
          const pending = await surveyMap(other);
          await page.evaluate(() => {window.blockPennantWrite=true;});
          await tap(cut(other));
          await page.evaluate(() => {window.blockPennantWrite=false;});
          assert.equal(await surveyMap(other), pending);
          await survey(other, 3, [2,1]);
          await beacon(other, 'X').selectOption(mode === 'mixed' ? '3' : '4');
          await beacon(other, 'Z').selectOption('3');
          await light(other, [], /move north/i);
          await beacon(other, 'Z').selectOption('2');
          await light(other, [0], /observer solo: Kite chime.*Pool/i);
          // Picking the other legal glass cell reverses which end can lead.
          const near = mode === 'mixed' ? 1 : 2;
          const far = near === 1 ? 2 : 1;
          await relay(other, far, []);
          await relay(other, near, [3-near]);
          if (mode === 'mixed') {
            await relay(other, far, [0,1,2]);
            await returnBeat(other, 'Start', [3-far]);
          }
        }
        const beforeCut = (await heard()).length;
        await tap(cut(other));
        assert.deepEqual(await storedPrism(), expectedPrism);
        assert(await post(other, 'Ridge').isDisabled());
        assert(await viewpoint(other, 1).isDisabled(), 'A new cut clears the walk and its survey');
        assert(await beacon(other, 'Light').isDisabled());
        assert.equal(await beacon(other, 'Z').inputValue(), '');
        assert(await relayStop(other, 1).isDisabled());
        assert(await ribbon(other,'Pick1').isDisabled());
        assert(await trace(other,'Pick1').isDisabled());
        assert(await lens(other,'Kite').isDisabled());
        assert(await cut(other).isDisabled());
        await tap(cut(other));
        assert.deepEqual(await storedPrism(), expectedPrism);
        assert.equal((await heard()).length, beforeCut, 'Cutting is silent');
        await playFacet(other, 'L', trio.notes, [0,1]);
        await tap(pairsTab);
        await playFacet(side, 'R', trio.notes, [1,2]);
        await tap(guideTab);
        assert.equal(await page.locator('#btScore_'+other).getAttribute('aria-label'), beatOne);
      }
    }
    await tap(pairsTab);
    const beforeBeatTwo = (await heard()).length;
    await tap(page.locator('#btNext_p'));
    assertAudio((await heard()).slice(beforeBeatTwo), trio, [1], audible);
    await tap(ring('p'));
    await choice('p','Role','harp').check();
    await choice('p','Note','moon').check();
    await tap(inlay('p'));
    assert(await cut('p').isEnabled(), 'A fresh solve allows a new cut');
    await play();
    assert(await ring('p').isEnabled(), 'Hearing another fanfare preserves both arcs');
    assert(await page.locator('#btNext_p').isEnabled(), 'Performing another fanfare preserves the current phrase');
    await take(1); // A new bell is one slot into the next chorus and remains silent.
    assert(await page.locator('#btNext_p').isDisabled(), 'A successful new collection clears the unfinished phrase');
    assert(await page.locator('#btBack_p').isDisabled());
    assert(await ring('p').isDisabled(), 'A new collection clears both arcs');
    assert(await inlay('p').isDisabled());
    assert.equal(await page.locator('#inFig_p').getAttribute('aria-label'), 'Inlay locked');
    assert.match(await loopStatus('p'), /new trio.*loop was cleared/i);
    assert.equal(await loopStatus('p'), await loopStatus('g'));
    assert(await cut('p').isDisabled());
    assert.match(await page.locator('#prStatus_p').innerText(), /cleared the uncut prism.*saved prism is still playable/i);
    assert.deepEqual(await storedPrism(), expectedPrism);
    await page.reload();
    assert.deepEqual(await heard(), []);
    assert(await collect('p').isDisabled());
    assert(await ring('p').isDisabled());
    assert(await inlay('p').isDisabled());
    assert.equal(await page.locator('#btScore_p').getAttribute('aria-label'), 'Baton score locked');
    assert.deepEqual(await saved(), {ver:1, solo:'bell', notes:seal.notes});
    assert(await cut('p').isDisabled());
    assert(await page.locator('#lwArch_p').isDisabled());
    assert(await page.locator('#svPick1_p').isDisabled());
    assert(await page.locator('#mbLight_p').isDisabled());
    assert(await page.locator('#brPick1_p').isDisabled());
    assert(await page.locator('#rrStart_p').isDisabled());
    assert(await page.locator('#stPick1_p').isDisabled());
    assert(await page.locator('#slKite_p').isDisabled());
    assert.deepEqual(await storedPrism(), expectedPrism);
    await playFacet('p', 'R', trio.notes, [1,2]);
    await tap(page.locator('#crStart_p'));
    await playFacet('p', 'L', trio.notes, [0,1]);
    await page.reload();
    assert.deepEqual(await heard(), []);
    await playFacet('p', 'R', trio.notes, [1,2]);
  }
  const [first, middle, last] = oldPrism.voices;
  for (const prism of [null, [], {ver:2, voices:oldPrism.voices}, {ver:1,voices:null},
    {ver:1, voices:[first, middle]}, {ver:1, voices:[first, middle, first]},
    {ver:1, voices:[null,middle,last]}, {ver:1, voices:[first,middle,{...last,note:'unknown'}]}]) {
    await page.evaluate(prism => localStorage.setItem('infinitycraft.field-guide.v1', JSON.stringify({
      prism, pennant:{ver:1,solo:'bell',notes:['kite','moon','ember']}
    })), prism);
    await page.reload();
    for (const side of ['g','p']) {
      assert(await page.locator('#crStart_'+side).isDisabled());
      assert(await page.locator('#crReverse_'+side).isDisabled());
      assert(await page.locator('#prL_'+side).isDisabled());
      assert(await page.locator('#prR_'+side).isDisabled());
      assert(await page.locator('#ffMain_'+side).isEnabled(), 'Invalid prism leaves the pennant playable');
    }
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
    console.log('PASS: all six Seal Cipher routes, twelve Refrains and 36 Cadences; unison and mute, correct solo and simultaneous answering pair, optional triad and independent seal playback; all 36 pennants, visible layers, explicit replacement, failed-save retry, reload and invalid saves. Kept-pennant fanfares passed for all three solos, distinct and repeated charts, cross-tab play and restart, muted and audible, with no seal. Mixed and replacement choruses passed with three collected pitches, one audio start, silent partials, failed-save recovery and fresh trios. Chorus Baton passed forward and backward solo playback, simultaneous final chords, mixed and replaced pitches, shared direction and beat across tabs, and clearing on a new collection. Infinity Loop passed both unlock orders, repeated-direction and partial-phrase guards, exact simultaneous duet pitches, mid-phrase playback across tabs and session resets. Loop Inlay passed the ring gate, separate instrument and pitch retries, cross-panel selection, one simultaneous triad per ring, an empty replay socket, and a fresh trio. Inlay Prism passed blocked-save recovery, unison instrument identity, both simultaneous facets, unique hatch patterns, cut and reset gates, mute, reload and invalid saves. Prism Crossing passed reverse unison clues, silent retries, cross-panel progress, exact duet and closing triad, storage failure and replacement, reload and free playback. Lantern Walk passed exact reverse solos at unison, silent duplicate and distant stops, both panels, completion, failed and successful prism replacement, restart and reload. Star Survey passed close unequal spans, either tied viewpoint, exact simultaneous far voices, silent retries, one answer, cross-panel play and reset.');
    console.log('PASS: Midpoint Beacon accepted both half-block neighbors, checked the chosen tied span, played the observer solo, and cleared on restart, replacement and reload.');
    console.log('PASS: Beacon Relay used the chosen glass, both tied leads, exact solo and simultaneous chord, mute, failed saves, cross-panel play and session resets.');
    console.log('PASS: Ribbon Return followed the actual tied relay order, played two solos and the endpoint duet, and preserved or cleared each beat across both panels as expected.');
    console.log('PASS: Star Lens projected the stored observer chart and solo for both tie orders, synchronized panels and silently reprojected across free play and a failed replacement.');
    console.log('PASS: Star Trace selected the densest Kite column, played its assigned unison instrument once, held the mark across tabs and cleared on a fresh walk.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
