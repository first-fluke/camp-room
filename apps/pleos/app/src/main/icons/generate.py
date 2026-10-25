#!/usr/bin/env python3
"""Deterministic, offline Pleos resolved-geometry + pinned Phosphor icon generation."""
from pathlib import Path
import argparse,base64,collections,hashlib,json,math,re,struct,xml.etree.ElementTree as ET
MAIN=Path(__file__).resolve().parent.parent
HERE=MAIN/'icons';KOTLIN=MAIN/'java/camp/room/app/icons';ASSETS=MAIN/'assets/icon-provenance'
COMMIT='2b75f3ad12b420c9504ef05df8d2564a28f8500e'
def sha(data):return hashlib.sha256(data).hexdigest()
def textjson(value):return json.dumps(value,ensure_ascii=False,indent=2)+'\n'
def guid(value):return value['sessionID'],value['localID']
def number(value):
 if not math.isfinite(value):raise ValueError('Non-finite coordinate')
 return format(value,'.9g')
def decode(raw):
 p=0;out=[]
 while p<len(raw):
  op=raw[p];p+=1
  if op not in {0,1,2,4}:raise ValueError(f'Unsupported Figma path opcode {op}')
  count={0:0,1:2,2:2,4:6}[op]
  if p+count*4>len(raw):raise ValueError('Truncated Figma path')
  values=struct.unpack_from('<'+'f'*count,raw,p);p+=count*4
  if not all(math.isfinite(v)for v in values):raise ValueError('Nonfinite Figma path')
  out.append((op,values))
 if not out or out[0][0]!=1:raise ValueError('Figma contour must start with move')
 return out
IDENT=(1,0,0,0,1,0)
def matrix(node):return tuple(node.get('transform',{}).get(k,d)for k,d in zip(['m00','m01','m02','m10','m11','m12'],IDENT))
def mul(a,b):return(a[0]*b[0]+a[1]*b[3],a[0]*b[1]+a[1]*b[4],a[0]*b[2]+a[1]*b[5]+a[2],a[3]*b[0]+a[4]*b[3],a[3]*b[1]+a[4]*b[4],a[3]*b[2]+a[4]*b[5]+a[5])
def point(m,x,y):return m[0]*x+m[1]*y+m[2],m[3]*x+m[4]*y+m[5]
def pleos_paths(source,rootguid):
 nodes={guid(n['guid']):n for n in source['nodes']};children=collections.defaultdict(list)
 for n in nodes.values():
  if 'parentIndex'in n:children[guid(n['parentIndex']['guid'])].append(n)
 root=nodes[tuple(rootguid)];paths=[];proof=[]
 def walk(n,transform,alpha,isroot=False):
  if not n.get('visible',True):return
  if not isroot:transform=mul(transform,matrix(n))
  alpha*=n.get('opacity',1)
  if n.get('effects')or n.get('isMask')or n.get('blendMode','NORMAL')not in ['NORMAL','PASS_THROUGH']:raise ValueError(f'Unsupported effect/mask/blend {n["guid"]}')
  rendered=False
  for kind in ['fill','stroke']:
   paints=[p for p in n.get(kind+'Paints',[])if p.get('visible',True)]
   if not paints:continue
   if len(paints)!=1 or paints[0].get('type')!='SOLID' or paints[0].get('blendMode','NORMAL')!='NORMAL':raise ValueError('Unsupported vector paint')
   geometry=n.get(kind+'Geometry',[])
   if not geometry:raise ValueError(f'Visible {kind} has no resolved geometry: {n["guid"]}')
   paint=paints[0];opacity=alpha*paint.get('opacity',1)*paint.get('color',{}).get('a',1)
   if not 0<=opacity<=1:raise ValueError('Invalid opacity')
   for shape in geometry:
    if shape['windingRule']not in ['NONZERO','ODD']:raise ValueError('Unknown winding')
    raw=base64.b64decode(source['blobs'][str(shape['commandsBlob'])]['base64'],validate=True);parts=[]
    for op,values in decode(raw):
     coordinates=[]
     for i in range(0,len(values),2):coordinates.extend(point(transform,values[i],values[i+1]))
     parts.append({0:'Z',1:'M',2:'L',4:'C'}[op]+' '.join(number(v)for v in coordinates))
    paths.append({'data':' '.join(parts),'alpha':opacity,'evenOdd':shape['windingRule']=='ODD'})
    proof.append({'node':n['guid'],'kind':kind,'commandsBlob':shape['commandsBlob'],'commandsSha256':sha(raw),'windingRule':shape['windingRule'],'opacity':opacity,'transform':transform})
   rendered=True
  if rendered and n['type']in ['VECTOR','BOOLEAN_OPERATION']:return
  if n['type']=='INSTANCE':raise ValueError(f'Visible unresolved instance {n["guid"]}')
  for child in sorted(children[guid(n['guid'])],key=lambda c:c['parentIndex']['position']):walk(child,transform,alpha)
 walk(root,IDENT,1,True)
 if not paths:raise ValueError(f'No visible geometry {rootguid}')
 return root,paths,proof
TOKEN=re.compile(r'[MmZzLlHhVvCcSsQqTtAa]|[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?')
def validate_path(data):
 tokens=[];pos=0
 for match in TOKEN.finditer(data):
  if data[pos:match.start()].strip(' ,\t\r\n'):raise ValueError('Unsupported SVG path token')
  tokens.append(match.group());pos=match.end()
 if data[pos:].strip(' ,\t\r\n')or not tokens or tokens[0]not in ['M','m']:raise ValueError('Invalid SVG path')
 i=0
 while i<len(tokens):
  command=tokens[i];i+=1
  if not command.isalpha():raise ValueError('Expected SVG command')
  args=[]
  while i<len(tokens)and not tokens[i].isalpha():args.append(float(tokens[i]));i+=1
  arity={'M':2,'Z':0,'L':2,'H':1,'V':1,'C':6,'S':4,'Q':4,'T':2,'A':7}[command.upper()]
  if not all(math.isfinite(x)for x in args)or(arity==0 and args)or(arity and(not args or len(args)%arity)):raise ValueError('Invalid SVG path arity')
  if command.upper()=='A':
   for j in range(0,len(args),7):
    if args[j+3]not in [0,1]or args[j+4]not in [0,1]:raise ValueError('Invalid arc flags')
def phosphor_paths(raw):
 if b'<!DOCTYPE'in raw:raise ValueError('Unsupported SVG doctype')
 svg=ET.fromstring(raw);box=[float(v)for v in svg.attrib['viewBox'].split()]
 if len(box)!=4 or box[:2]!=[0,0]or box[2]<=0 or box[3]<=0:raise ValueError('Unsupported viewport')
 paths=[]
 def walk(node,alpha=1,fill='currentColor',rule='nonzero'):
  tag=node.tag.rsplit('}',1)[-1]
  allowed={'svg':{'width','height','viewBox','fill'},'g':{'fill','opacity','fill-opacity','fill-rule'},'path':{'d','fill','opacity','fill-opacity','fill-rule'},'rect':{'x','y','width','height','fill'}}
  if tag not in allowed or set(node.attrib)-allowed[tag]:raise ValueError(f'Unsupported SVG element/attribute {tag}: {set(node.attrib)-allowed.get(tag,set())}')
  alpha*=float(node.attrib.get('opacity',1))*float(node.attrib.get('fill-opacity',1));fill=node.attrib.get('fill',fill);rule=node.attrib.get('fill-rule',rule)
  if not 0<=alpha<=1 or rule not in ['nonzero','evenodd']:raise ValueError('Unsupported SVG opacity/rule')
  if tag=='rect':
   if fill!='none':raise ValueError('Only explicit invisible SVG framing rect supported')
   return
  if tag=='path':
   if fill not in ['currentColor','#000','#000000','black']:raise ValueError('Unsupported SVG fill')
   data=node.attrib['d'];validate_path(data);paths.append({'data':data,'alpha':alpha,'evenOdd':rule=='evenodd'})
  for child in node:walk(child,alpha,fill,rule)
 walk(svg)
 if not paths:raise ValueError('Empty Phosphor icon')
 return box[2:],paths

def svg_text(width,height,paths):
 rows=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {number(width)} {number(height)}">']
 for p in paths:rows.append(f'<path d="{p["data"]}" fill="#131417" fill-opacity="{number(p["alpha"])}" fill-rule="{"evenodd"if p["evenOdd"]else"nonzero"}"/>')
 return '\n'.join(rows+['</svg>'])+'\n'
def kotlin_value(name,width,height,paths):
 entries=',\n'.join('            CampIconPath('+json.dumps(p['data'])+','+number(p['alpha'])+'f,'+str(p['evenOdd']).lower()+')'for p in paths)
 return f'    val {name}: ImageVector by lazy {{\n        campVector("{name}",{number(width)}f,{number(height)}f,listOf(\n{entries}\n        ))\n    }}\n'
def main(check=False):
 outputs={};sourcefile=HERE/'pleos-source.json';data=json.loads(sourcefile.read_text());official=[];body=[]
 for icon in data['icons']:
  source=data['sources'][icon['source']];root,paths,proof=pleos_paths(source,icon['guid']);width=root['size']['x'];height=root['size']['y'];name=icon['name']
  svg=svg_text(width,height,paths);outputs[HERE/'pleos'/f'{name}.svg']=svg;body.append(kotlin_value(name,width,height,paths))
  official.append({**icon,'upstream':source['upstream'],'figSha256':source['figSha256'],'viewport':[width,height],'svgSha256':sha(svg.encode()),'geometry':proof})
 outputs[KOTLIN/'CampPleos.kt']='// Generated offline from official Pleos resolved geometry. Do not hand edit.\npackage camp.room.app.icons\n\nimport androidx.compose.ui.graphics.vector.ImageVector\n\ninternal object CampPleos {\n'+''.join(body)+'}\n'
 outputs[ASSETS/'pleos.json']=textjson({'schemaVersion':1,'source':'Official Pleos Connect design assets and display guide 2.1.5','rights':'Hyundai Motor Group / Pleos; separate from Phosphor MIT. See licenses/Pleos-HMG-design-assets.txt.','geometrySource':'Exact resolved fillGeometry/strokeGeometry commands; visible inheritance and affine transforms; hidden operands excluded. Archived vectorNetwork is not guessed or reconstructed.','selectedSourceSha256':sha(sourcefile.read_bytes()),'generatorSha256':sha(Path(__file__).read_bytes()),'icons':official})
 phosphor=[];groups={}
 package=json.loads((HERE/'phosphor/upstream/package.json').read_text())
 for path in sorted((HERE/'phosphor/upstream/assets').glob('*/*.svg')):
  weight=path.parent.name;stem=path.stem.removesuffix('-'+weight) if weight!='regular' else path.stem;name=''.join(s.title()for s in stem.split('-'));raw=path.read_bytes();(width,height),paths=phosphor_paths(raw);groups.setdefault(weight,[]).append(kotlin_value(name,width,height,paths));phosphor.append({'name':name,'weight':weight,'upstream':f'https://github.com/phosphor-icons/core/blob/{COMMIT}/assets/{weight}/{path.name}','sha256':sha(raw),'bytes':len(raw),'pathCount':len(paths)})
 text='// Generated from pinned official Phosphor SVGs. MIT license in app assets.\npackage camp.room.app.icons\n\nimport androidx.compose.ui.graphics.vector.ImageVector\n\ninternal object CampPhosphor {\n'+''.join(groups.pop('regular',[]))
 for weight,body in sorted(groups.items()):text+='    object '+weight.title()+' {\n'+''.join('    '+line if line else line for line in ''.join(body).splitlines(True))+'    }\n'
 outputs[KOTLIN/'CampPhosphor.kt']=text+'}\n'
 outputs[ASSETS/'phosphor.json']=textjson({'schemaVersion':1,'upstream':'https://github.com/phosphor-icons/core','commit':COMMIT,'package':package['name'],'version':package['version'],'license':'MIT','licenseSha256':sha((HERE/'phosphor/upstream/LICENSE').read_bytes()),'generatorSha256':sha(Path(__file__).read_bytes()),'icons':phosphor})
 for path,text in outputs.items():
  if check:
   if not path.exists()or path.read_text()!=text:raise ValueError(f'Generated icon drift: {path.relative_to(MAIN)}')
  else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
 print(f'{"CHECKED"if check else"GENERATED"}: {len(official)} official Pleos icons, {len(phosphor)} pinned Phosphor SVGs, {len(outputs)} deterministic outputs')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');main(parser.parse_args().check)
