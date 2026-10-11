from PIL import Image
from pathlib import Path
import json, shutil, hashlib
ROOT=Path(__file__).resolve().parents[2]
ART=Path(__file__).resolve().parent
ASSETS=ROOT/'java/src/main/resources/assets/convergence';BED=ROOT/'bedrock/resource_pack'
FILES={'convergence':'convergence-source.png','aurora':'aurora-source.png','gear':'gear-source.png'}
# These are mechanical UV crops from generated art, not newly painted pixels.
UV={'head_top':(8,0,16,8),'head_bottom':(16,0,24,8),'head_right':(0,8,8,16),'head_front':(8,8,16,16),'head_left':(16,8,24,16),'head_back':(24,8,32,16),'leg_top':(4,16,8,20),'leg_bottom':(8,16,12,20),'leg_right':(0,20,4,32),'leg_front':(4,20,8,32),'leg_left':(8,20,12,32),'leg_back':(12,20,16,32),'body_top':(20,16,28,20),'body_bottom':(28,16,36,20),'body_right':(16,20,20,32),'body_front':(20,20,28,32),'body_left':(28,20,32,32),'body_back':(32,20,40,32),'arm_top':(44,16,48,20),'arm_bottom':(48,16,52,20),'arm_right':(40,20,44,32),'arm_front':(44,20,48,32),'arm_left':(48,20,52,32),'arm_back':(52,20,56,32)}
SOURCES={
'convergence':{'head_bottom':(8,0,16,8),'body_top':(28,16,36,20),'body_bottom':(28,29,36,32),'body_right':(20,20,24,32),'body_front':(24,20,40,32),'body_left':(40,20,44,32),'body_back':(24,20,40,32),'arm_top':(50,16,54,20),'arm_bottom':(50,29,54,32),'arm_right':(44,20,48,32),'arm_front':(48,20,56,32),'arm_left':(56,20,60,32),'arm_back':(48,20,56,32)},
'aurora':{'head_top':(8,0,16,5),'head_bottom':(8,0,16,5),'head_right':(0,5,8,12.5),'head_front':(8,5,16,12.5),'head_left':(16,5,24,12.5),'head_back':(24,5,32,12.5),'leg_top':(1,16,5,20),'leg_bottom':(1,29,5,32),'body_top':(24,16,40,20),'body_bottom':(24,29,40,32),'body_right':(16,16,24,32),'body_front':(24,16,40,32),'body_left':(40,16,44,32),'body_back':(24,16,40,32),'arm_top':(48,16,52,20),'arm_bottom':(48,29,52,32),'arm_right':(44,16,48,32),'arm_front':(48,16,56,32),'arm_left':(56,16,60,32),'arm_back':(60,16,63,32)}}
manifest={'source_files':FILES,'armor':{},'icons':{}}
for collection in ['convergence','aurora']:
 src=Image.open(ART/(collection+'-source.png')).convert('RGBA');out=Image.new('RGBA',(128,64))
 for face,box in UV.items():
  crop=SOURCES[collection].get(face,box);px=tuple(round(v*src.width/64) for v in crop)
  dest=tuple(v*2 for v in box);tile=src.crop(px).resize((dest[2]-dest[0],dest[3]-dest[1]),Image.Resampling.NEAREST)
  # Faint alpha from the generator must not turn solid armor into see-through plates.
  # Fill any UV border holes with the closest existing artwork sample.
  pixels=tile.load();opaque=[(x,y) for y in range(tile.height) for x in range(tile.width) if pixels[x,y][3]>=128]
  assert opaque,face
  for y in range(tile.height):
   for x in range(tile.width):
    rgba=pixels[x,y]
    if rgba[3]<128:
     sx,sy=min(opaque,key=lambda p:(p[0]-x)**2+(p[1]-y)**2);rgba=pixels[sx,sy]
    pixels[x,y]=(*rgba[:3],255)
  out.paste(tile,dest[:2])
 name=collection+'_refresh19'
 for layer in ['humanoid','humanoid_leggings']:
  p=ASSETS/f'textures/entity/equipment/{layer}/{name}.png';p.parent.mkdir(parents=True,exist_ok=True);out.save(p)
 bed=BED/f'textures/convergence/models/armor/refresh19/{name}.png';bed.parent.mkdir(parents=True,exist_ok=True);out.save(bed)
 manifest['armor'][collection]={'size':[128,64],'uv_rectangles':UV,'source_rectangles':SOURCES[collection]}
 eq=ASSETS/f'equipment/{"armor" if collection=="convergence" else "aurora_armor"}.json';data=json.loads(eq.read_text())
 for layer in ['humanoid','humanoid_leggings']:data['layers'][layer]=[{'texture':'convergence:'+name}]
 eq.write_text(json.dumps(data,indent=2)+'\n')
icons=['helmet','chestplate','leggings','boots','sword','mace','aurora_helmet','aurora_chestplate','aurora_leggings','aurora_boots','spear','shield','pickaxe','axe','shovel','hoe','bow','crossbow','ingot','totem','infinity_arrow','void_arrow','starfire_arrow','builder_wand']
BOUNDS=[(44,33,222,235),(273,43,496,235),(545,39,707,235),(785,82,988,225),(1048,27,1262,245),(1306,21,1517,239),(34,289,229,492),(271,297,497,493),(534,298,719,493),(781,337,992,482),(1042,274,1261,497),(1322,265,1502,513),(41,537,240,735),(286,523,492,736),(536,541,732,738),(791,539,991,738),(1047,528,1254,738),(1313,554,1508,733),(33,802,238,958),(278,754,493,991),(535,782,727,986),(789,782,979,987),(1051,782,1243,986),(1301,771,1504,979)]
atlas=Image.open(ART/'gear-source.png').convert('RGBA');review=Image.new('RGBA',(6*128,4*128),'#202734')
for index,name in enumerate(icons):
 col=index%6;row=index//6;b=BOUNDS[index];box=(b[0]-2,b[1]-2,b[2]+2,b[3]+2)
 icon=Image.new('RGBA',(64,64));sprite=atlas.crop(box);sprite.thumbnail((56,56),Image.Resampling.NEAREST);icon.paste(sprite,((64-sprite.width)//2,(64-sprite.height)//2))
 p=ASSETS/f'textures/item/refresh19/{name}.png';p.parent.mkdir(parents=True,exist_ok=True);icon.save(p)
 p=BED/f'textures/convergence/items/refresh19/{name}.png';p.parent.mkdir(parents=True,exist_ok=True);icon.save(p)
 model=ASSETS/f'models/item/{name}.json';data=json.loads(model.read_text()) if model.exists() else {'parent':'minecraft:item/handheld','textures':{}}
 data['textures']['layer0']='convergence:item/refresh19/'+name;model.write_text(json.dumps(data,indent=2)+'\n')
 if name in ['pickaxe','axe','shovel','hoe']:
  (ASSETS/f'items/{name}.json').write_text(json.dumps({'model':{'type':'minecraft:model','model':'convergence:item/'+name}},indent=2)+'\n')
 manifest['icons'][name]={'source_rectangle':box,'size':[64,64]}
 review.alpha_composite(icon.resize((128,128),Image.Resampling.NEAREST),(col*128,row*128))
# Both wand variants keep their distinct behavior and use the same fresh collection art.
for base in [ASSETS/'textures/item',BED/'textures/convergence/items']:shutil.copy2(base/'refresh19/builder_wand.png',base/'refresh19/sculptor_wand.png')
model=ASSETS/'models/item/sculptor_wand.json';data=json.loads(model.read_text());data['textures']['layer0']='convergence:item/refresh19/sculptor_wand';model.write_text(json.dumps(data,indent=2)+'\n')
atlaspath=BED/'textures/item_texture.json';data=json.loads(atlaspath.read_text())
for name in icons+['sculptor_wand']:data['texture_data']['convergence_'+name]={'textures':'textures/convergence/items/refresh19/'+name}
atlaspath.write_text(json.dumps(data,indent=2)+'\n')
review.save(ART/'inventory-preview.png')
manifest['sha256']={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for root in [ASSETS/'textures',BED/'textures'] for p in root.rglob('*.png') if 'refresh19' in p.as_posix()}
(ART/'uv-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Packed two complete armor UV atlases and 25 icons; originals retained.')
