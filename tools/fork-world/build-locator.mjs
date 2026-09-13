import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const input=process.argv[2];
if(!input)throw new Error('Usage: node tools/fork-world/build-locator.mjs <pinned-source-feature.geojson>');
const bytes=fs.readFileSync(input), source=JSON.parse(bytes), sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const upstream=JSON.parse(fs.readFileSync(path.join(root,'data/fork-world/locator/upstream-record.json')));
assert.equal(sha(bytes),upstream.subsetSha256,'Input must be the pinned, unmodified selected upstream feature.');
const candidates=source.features?.filter(f=>f.properties.ADM0_A3==='SGP');
assert.equal(candidates?.length,1,'Require exactly one Singapore ADM0_A3=SGP feature.');
const feature=candidates[0];
assert(['Polygon','MultiPolygon'].includes(feature.geometry.type));
const polygons=feature.geometry.type==='Polygon'?[feature.geometry.coordinates]:feature.geometry.coordinates;
const coords=polygons.flat(2);assert(coords.length>20,'Reject placeholder geometry.');
for(const [lng,lat]of coords){assert(Number.isFinite(lng)&&Number.isFinite(lat));assert(lng>100&&lng<106&&lat>0&&lat<3);}
for(const polygon of polygons)for(const ring of polygon){assert(ring.length>=4);assert.deepEqual(ring[0],ring.at(-1));}
const bbox=[Math.min(...coords.map(c=>c[0])),Math.min(...coords.map(c=>c[1])),Math.max(...coords.map(c=>c[0])),Math.max(...coords.map(c=>c[1]))];
const geo={type:'FeatureCollection',name:'Singapore_Natural_Earth_Admin0_v5_1_1',features:[{type:'Feature',properties:{ADM0_A3:'SGP',ADMIN:feature.properties.ADMIN,source:'Natural Earth',version:'5.1.1',scale:'1:10,000,000'},geometry:feature.geometry}]};
const dataPath=path.join(root,'data/fork-world/locator/singapore-ne-5.1.1.geojson');
fs.writeFileSync(dataPath,JSON.stringify(geo,null,2)+'\n');
// Local equirectangular display: east right, north up. Geometry is not simplified.
const lat0=(bbox[1]+bbox[3])/2, cos=Math.cos(lat0*Math.PI/180), width=(bbox[2]-bbox[0])*cos,height=bbox[3]-bbox[1];
const scale=Math.min(1150/width,590/height), x0=80+(1150-width*scale)/2,y0=255+(590-height*scale)/2;
const project=([lng,lat])=>[x0+(lng-bbox[0])*cos*scale,y0+(bbox[3]-lat)*scale];
const paths=polygons.map((poly,i)=>`<path id="sgp-part-${i}" d="${poly.map(ring=>ring.map((p,j)=>`${j?'L':'M'}${project(p).map(v=>v.toFixed(3)).join(',')}`).join(' ')+' Z').join(' ')}"/>`).join('\n');
const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" viewBox="0 0 1920 1080" role="img" aria-labelledby="title desc">
<title id="title">Singapore locator and fictional FORK court</title>
<desc id="desc">Natural Earth Admin 0 version 5.1.1 generalized Singapore outline. Locator only, not playable coverage. The separate fictional 16 by 16 block court has no accepted geographic position. Small islands and offshore features may be omitted by the source. No court map marker is shown.</desc>
<rect width="1920" height="1080" fill="#101e29"/>
<g font-family="Arial,Helvetica,sans-serif" fill="#f1f5ef">
<text x="88" y="90" font-size="24" letter-spacing="5" fill="#9eb1b8">FORK / PLACE</text>
<text x="84" y="188" font-size="88" font-weight="700">Singapore</text>
<text x="90" y="237" font-size="28" fill="#aec1c8">Generalized main-island locator</text>
<g fill="#88cfc5" stroke="#c3ece0" stroke-width="2" fill-rule="evenodd">${paths}</g>
<g transform="translate(1180 280)"><path d="M0 55 V0 M-10 15 L0 0 L10 15" fill="none" stroke="#aec1c8" stroke-width="3"/><text x="-10" y="-15" font-size="24">N</text></g>
<path d="M1320 280 V840" stroke="#3c505b" stroke-width="2"/>
<text x="1390" y="340" font-size="24" letter-spacing="3" fill="#9eb1b8">SCORED PLAY AREA</text>
<text x="1390" y="412" font-size="48" font-weight="700">Fictional court</text>
<text x="1390" y="471" font-size="34">16 × 16 blocks</text>
<text x="1390" y="560" font-size="27" fill="#aec1c8">Clinic · workshop · generator</text>
<text x="1390" y="644" font-size="27" fill="#aec1c8">No accepted geographic</text>
<text x="1390" y="682" font-size="27" fill="#aec1c8">position or map marker.</text>
<text x="1390" y="776" font-size="27" fill="#88cfc5">Locator ≠ playable coverage</text>
<text x="88" y="930" font-size="27" fill="#c2d0d5">Main-island outline only. Offshore islands are not depicted.</text>
<text x="88" y="973" font-size="23" fill="#9eb1b8">Pedra Branca is not separately depicted. No full-island Minecraft coverage is claimed.</text>
<text x="88" y="1030" font-size="23" fill="#9eb1b8">Made with Natural Earth · Admin 0 v5.1.1 · 1:10m · Public domain · naturalearthdata.com</text>
</g></svg>\n`;
const svgPath=path.join(root,'assets/fork-world/locator/singapore-locator.svg');fs.writeFileSync(svgPath,svg);
const manifest={schema:'fork-locator-1',createdUtc:new Date().toISOString(),source:{name:'Natural Earth Admin 0 Countries',version:'5.1.1',scaleDenominator:10000000,officialPage:'https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-0-countries/',dataUrl:'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.1/geojson/ne_10m_admin_0_countries.geojson',sha256:sha(bytes),bytes:bytes.length,filter:'ADM0_A3 == SGP',featureCount:1,geometryType:feature.geometry.type,polygonCount:polygons.length,ringCount:polygons.reduce((n,p)=>n+p.length,0),vertexCount:coords.length,license:'Public domain',licenseUrl:'https://www.naturalearthdata.com/about/terms-of-use/',attribution:'Made with Natural Earth.',sourceSurveyDate:null},coordinates:{crs:'OGC:CRS84',datum:'WGS84',axisOrder:['longitude_deg','latitude_deg'],bboxLngLat:bbox,bboxMinLatMinLngMaxLatMaxLng:[bbox[1],bbox[0],bbox[3],bbox[2]],displayProjection:'local equirectangular, standard parallel = bbox midpoint latitude',standardParallelDegrees:lat0,svgProject:{scalePixelsPerDegreeLatitude:scale,x0,y0,northLatitude:bbox[3],westLongitude:bbox[0],longitudeCosine:cos},simplification:'None beyond upstream geometry; SVG coordinates rounded to 0.001 pixel.'},coverage:{geographicLocator:'All polygons of the source SGP feature, displayed without cropping.',limitations:['Generalized 1:10m source, not current survey/coastline authority.','Small/offshore islands may be omitted. Pedra Branca is not separately depicted.','No full-island or district Minecraft coverage accepted.','No authentic floorplans verified.'],playable:{layoutId:'fork-court-v1',label:'Fictional 16 x 16 block scored court',courtShapeSha256:'538149df807a946101e7f8a14806aa3464b874ad4b3ae626451291ca2f3d9e14',dimension:'minecraft:overworld',origin:[0,64,0],relativeBoundsInclusive:[0,0,0,15,15,15],geographicCourtAnchor:null,courtMapMarker:false,livePlacementAccepted:false}},outputs:[{path:'data/fork-world/locator/singapore-ne-5.1.1.geojson',sha256:sha(fs.readFileSync(dataPath))},{path:'assets/fork-world/locator/singapore-locator.svg',sha256:sha(fs.readFileSync(svgPath))}],usage:'Static locator only. Cinematic renders and integrates on Laptop. Do not add a geographic court marker until Main accepts that anchor.'};
manifest.source.selectedInputSha256=manifest.source.sha256;
manifest.source.sha256=upstream.sha256;
manifest.source.bytes=upstream.bytes;
manifest.source.gitBlobSha1=upstream.gitBlobSha1;
manifest.source.retainedTermsSummary={path:'docs/fork-world/LOCATOR-CREDITS.txt',sha256:sha(fs.readFileSync(path.join(root,'docs/fork-world/LOCATOR-CREDITS.txt')))};
manifest.coverage.limitations[1]='The source SGP feature has one polygon and no separate offshore-island outlines. Pulau Ubin, Pulau Tekong, Sentosa and Pedra Branca are not individually depicted; no offshore inset is included.';
manifest.coverage.geographicLocator='Complete single-polygon SGP source feature; generalized main-island locator, displayed without cropping.';
fs.writeFileSync(path.join(root,'data/fork-world/locator/source-manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({bbox,polygons:polygons.length,vertices:coords.length,upstreamSha256:upstream.sha256,selectedInputSha256:sha(bytes),svgSha256:sha(fs.readFileSync(svgPath))}));
