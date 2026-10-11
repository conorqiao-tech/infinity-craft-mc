const status=document.getElementById('copy-status');
const search=document.getElementById('reward-search');
let statusTimer, filter='all';
const placeSearch=document.getElementById('place-search');
let placeFilter='all';
function filterPlaces(){
 const query=placeSearch.value.trim().toLowerCase();let visible=0;
 document.querySelectorAll('.place').forEach(card=>{
  card.hidden=(placeFilter!=='all'&&card.dataset.kind!==placeFilter)||!card.dataset.search.includes(query);
  if(!card.hidden)visible++;
 });
 document.getElementById('place-count').textContent=visible===0?'No matching places. Try another name.':`${visible} place${visible===1?'':'s'}`;
}
placeSearch.addEventListener('input',filterPlaces);
document.querySelectorAll('[data-place-filter]').forEach(button=>button.addEventListener('click',()=>{
 placeFilter=button.dataset.placeFilter;
 document.querySelectorAll('[data-place-filter]').forEach(other=>{const selected=other===button;other.classList.toggle('selected',selected);other.setAttribute('aria-pressed',String(selected));});
 filterPlaces();
}));
function filterRewards(){
 const query=search.value.trim().toLowerCase();let visible=0;
 document.querySelectorAll('.reward').forEach(card=>{
  card.hidden=(filter!=='all'&&!card.classList.contains(filter))||!card.dataset.search.includes(query);
  if(!card.hidden)visible++;
 });
 document.getElementById('reward-count').textContent=visible===0?'No matching rewards. Try another name or achievement.':`${visible} reward${visible===1?'':'s'}`;
}
search.addEventListener('input',filterRewards);
document.querySelectorAll('[data-filter]').forEach(button=>button.addEventListener('click',()=>{
 filter=button.dataset.filter;
 document.querySelectorAll('[data-filter]').forEach(other=>{const selected=other===button;other.classList.toggle('selected',selected);other.setAttribute('aria-pressed',String(selected));});
 filterRewards();
}));
document.addEventListener('click',async event=>{
 const button=event.target.closest('[data-copy]');if(!button)return;
 try {await navigator.clipboard.writeText(button.dataset.copy);status.textContent='Command copied';}
 catch {status.textContent='Copy this command: '+button.dataset.copy;}
 status.classList.add('visible');clearTimeout(statusTimer);statusTimer=setTimeout(()=>status.classList.remove('visible'),5000);
});
