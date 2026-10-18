"""Build the static reward guide from its catalogue. No runtime services needed."""
from pathlib import Path
from html import escape
import json
root=Path(__file__).parent
catalogue=json.loads((root/'catalogue.json').read_text())
places=[]
labels={'lobby':'LOBBY','mode':'GAME MODE','minigame':'MINIGAME','map':'ADVENTURE MAP'}
for id,name,kind,description,command,art in catalogue['places']:
 search=escape((name+' '+kind+' '+description).lower(),quote=True)
 places.append(f'''<article class="place" data-kind="{escape(kind,quote=True)}" data-search="{search}"><div class="place-art"><img src="{escape(art,quote=True)}" width="96" height="96" alt="" loading="lazy"></div><div class="place-content"><span class="place-type">{labels[kind]}</span><h3>{escape(name)}</h3><p>{escape(description)}</p><div class="place-command"><code>{escape(command)}</code><button type="button" data-copy="{escape(command,quote=True)}" aria-label="Copy {escape(name,quote=True)} command">Copy</button></div></div></article>''')
cards=[]
for kind,key in [('power','powers'),('cosmetic','cosmetics')]:
 for id,name,goal,description in catalogue[key]:
  command=f'/{"power" if kind=="power" else "cosmetic"} {id}'
  cards.append(f'''<article class="reward {kind}" data-search="{escape((name+' '+goal+' '+description).lower(),quote=True)}"><span class="type">{'POWER PRESET' if kind=='power' else 'COSMETIC AURA'}</span><h3>{escape(name)}</h3><p class="unlock">{escape(goal)}</p><p>{escape(description)}</p><div class="command"><code>{command}</code><button type="button" data-copy="{command}" aria-label="Copy {escape(name)} command">Copy</button></div></article>''')
rows=[]
for id,name,cost in catalogue['trades']:
 rows.append(f'<tr><th scope="row">{escape(name)}</th><td>{escape(cost)}</td><td><code>/trade {id}</code><button class="copy-small" type="button" data-copy="/trade {id}" aria-label="Copy {escape(name)} trade preview">Copy</button></td></tr>')
shop=json.loads((root.parent/'java/src/main/resources/assets/convergence/infini_catalogue.json').read_text())
assert shop['unitsPerInfini']==100 and shop['usdPerInfiniCents']==200 and shop['paymentsEnabled'] is False
products={p['id']:p for p in shop['products']}
support=[]
from decimal import Decimal
def amount(integer,scale=100):
 return format(Decimal(integer)/scale,'f').rstrip('0').rstrip('.') if integer%scale else str(integer//scale)
styles={'go':'Copper Comet','plus':'Aurora Helix','pro':'Rift Halo','ultra':'Starlight Crown','admin':'Separate owner approval required'}
for tier,style in styles.items():
 monthly,permanent=products[tier+'_pass'],products[tier+'_lifetime']
 for product in (monthly,permanent):
  assert product['usdCents']*shop['unitsPerInfini']==product['units']*shop['usdPerInfiniCents']
 label='ADMIN APPLICATION' if tier=='admin' else tier.upper()+' SUPPORTER'
 support.append(f'''<article data-plan="{tier}"><span class="rank-label {tier}">{label}</span><p class="support-price">${amount(monthly['usdCents'])} <small>USD / 30 days</small></p><p>${amount(permanent['usdCents'])} USD permanent</p><p>{amount(monthly['units'])} / {amount(permanent['units'])} Infini</p><p>{escape(style)}</p><button type="button" disabled aria-label="Public checkout is not connected">Checkout not connected</button></article>''')
html=((root/'site-template.html').read_text()
      .replace('$PLACE_CARDS','\n'.join(places))
      .replace('$REWARD_CARDS','\n'.join(cards))
      .replace('$TRADE_ROWS','\n'.join(rows))
      .replace('$SUPPORT_PLANS','\n'.join(support)))
(root/'dist/index.html').write_text(html)
