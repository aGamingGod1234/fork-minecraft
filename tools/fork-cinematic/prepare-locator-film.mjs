import fs from 'node:fs';
import crypto from 'node:crypto';
const prior=fs.readFileSync('media/edit/locator-cbd.svg','utf8');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
if(hash(prior)!=='7a81a826d91ede4f2133c1081d0c7190dcc495903fdcf84e1fa2c20cab16cb55')throw Error('Unexpected prior locator');
const provenance=JSON.parse(fs.readFileSync('media/edit/locator-cbd-provenance.json','utf8'));
const outline=prior.match(/<path id="sgp-part-0"[^>]+\/>/)[0];
const {x,y}=provenance.marker;
const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" viewBox="0 0 1920 1080" role="img" aria-labelledby="title desc">
<title id="title">Singapore - Market Street CBD</title>
<desc id="desc">Map orientation. The marked 256 by 256 metre district is the generated area. Generalized main-island outline from Natural Earth; extended scope and source details accompany the film.</desc>
<rect width="1920" height="1080" fill="#101e29"/>
<g font-family="Arial,Helvetica,sans-serif" fill="#f1f5ef">
<text x="84" y="165" font-size="88" font-weight="700">Singapore</text>
<g fill="#88cfc5" stroke="#c3ece0" stroke-width="2" fill-rule="evenodd">${outline}</g>
<circle cx="${x.toFixed(3)}" cy="${y.toFixed(3)}" r="13" fill="#ffd17a" stroke="#101e29" stroke-width="5"/>
<path d="M${x.toFixed(3)} ${y.toFixed(3)} L1280 760 L1350 760" stroke="#ffd17a" stroke-width="3" fill="none"/>
<text x="1350" y="645" font-size="45" font-weight="700">Market Street</text>
<text x="1350" y="703" font-size="45" font-weight="700">CBD</text>
<text x="1350" y="817" font-size="32" fill="#ffd17a">256 x 256 metres</text>
<text x="88" y="956" font-size="30" fill="#c2d0d5">Map orientation. The marked district is built.</text>
<text x="88" y="1030" font-size="22" fill="#9eb1b8">Made with Natural Earth | Public domain | naturalearthdata.com</text>
</g></svg>`;
fs.writeFileSync('media/edit/locator-film-v3.svg',svg);
fs.writeFileSync('media/edit/locator-film-v3.html',`<!doctype html><meta charset="utf-8"><title>FORK locator film v3</title><style>html,body{margin:0;width:1920px;height:1080px;overflow:hidden;background:#101e29}svg{display:block}</style>${svg}`);
fs.writeFileSync('media/edit/locator-film-v3-provenance.json',JSON.stringify({...provenance,utc:new Date().toISOString(),priorSvgSha256:hash(prior),outputSvg:'media/edit/locator-film-v3.svg',outputSvgSha256:hash(svg),modifications:'Film copy reduced; identical source outline and district marker. Extended limitations in docs/fork-cinematic/locator-notices.md.'},null,2));
console.log('Film locator v3 prepared; previous PNG preserved.');
