// Original FORK pixel art, authored on the classic 64x64 Minecraft UV layout.
// No account downloads, generated-image service, or third-party artwork.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const output=path.join(root,'src/main/resources/assets/fork/textures/entity');
const proof=path.join(root,'.work/fork/gameplay');
const rgba=hex=>[...hex.matchAll(/[a-f\d]{2}/gi)].map(x=>parseInt(x[0],16)).concat(255).slice(0,4);
function canvas(w,h){return {w,h,pixels:Buffer.alloc(w*h*4)};}
function pixel(im,x,y,c){if(x<0||x>=im.w||y<0||y>=im.h)throw Error('Pixel outside atlas'); im.pixels.set(typeof c==='string'?rgba(c):c,(y*im.w+x)*4);}
function rect(im,x,y,w,h,c){for(let dy=0;dy<h;dy++)for(let dx=0;dx<w;dx++)pixel(im,x+dx,y+dy,c);}
function shade(hex,f){return rgba(hex).slice(0,3).map(x=>Math.round(Math.min(255,x*f)).toString(16).padStart(2,'0')).join('');}
function cube(im,u,v,w,h,d,c){
  rect(im,u+d,v,w,d,shade(c,1.12)); rect(im,u+d+w,v,w,d,shade(c,.65));
  rect(im,u,v+d,d,h,shade(c,.80)); rect(im,u+d,v+d,w,h,c);
  rect(im,u+d+w,v+d,d,h,shade(c,.90)); rect(im,u+2*d+w,v+d,w,h,shade(c,.76));
}
const palette={
  medic:{cloth:'148c89',light:'59bbb0',dark:'085b62',pants:'163d50',skin:'be8768',hair:'292e35',coat:'e9f0e8',accent:'9cdec9'},
  engineer:{cloth:'e8a62d',light:'ffd269',dark:'a96620',pants:'1b3049',skin:'9c654e',hair:'27242a',coat:'213c57',accent:'fff0ad'},
  courier:{cloth:'2857b8',light:'6c8fe0',dark:'17367c',pants:'192b4b',skin:'d6a480',hair:'443327',coat:'315cba',accent:'ddb06c'},
};
function face(im,p){
  cube(im,0,0,8,8,8,p.skin);
  // Hair, eyes, brows and jaw are explicit pixels, not photo sampling.
  rect(im,8,8,8,2,p.hair); rect(im,8,10,1,3,p.hair);rect(im,15,10,1,3,p.hair);
  rect(im,0,8,8,4,p.hair);rect(im,16,8,8,4,p.hair);rect(im,24,8,8,6,p.hair);
  rect(im,8,0,8,8,shade(p.hair,1.08));
  pixel(im,10,11,p.hair);pixel(im,13,11,p.hair);pixel(im,10,12,'ebeee5');pixel(im,13,12,'ebeee5');
  pixel(im,11,12,'263642');pixel(im,14,12,'263642');pixel(im,12,13,shade(p.skin,.82));
  rect(im,11,15,3,1,shade(p.skin,.70));
}
function limbs(im,p){
  for(const [u,v] of [[0,16],[16,48]]){
    cube(im,u,v,4,12,4,p.pants);
    rect(im,u,v+13,16,3,'18232e'); // Boots continue around all four faces.
    rect(im,u+4,v+13,4,1,'55646b'); rect(im,u+4,v+15,4,1,'101b25');
    rect(im,u+4,v+5,1,6,shade(p.pants,1.22));
  }
  for(const [u,v] of [[40,16],[32,48]]){
    cube(im,u,v,4,12,4,p.cloth);
    rect(im,u,v+12,16,4,p.skin);
    rect(im,u+4,v+12,4,1,shade(p.cloth,.7));
    rect(im,u+4,v+5,1,6,p.light);
    rect(im,u+8,v+4,4,8,p.dark);
  }
}
function make(role){
  const p=palette[role],im=canvas(64,64);face(im,p);limbs(im,p);cube(im,16,16,8,12,4,p.cloth);
  rect(im,23,20,2,2,p.skin);rect(im,22,22,4,1,p.dark);
  rect(im,20,30,8,2,p.pants);rect(im,20,29,8,1,'233142');
  pixel(im,24,29,p.accent);
  if(role==='medic'){
    // White clinic coat, teal placket, two pockets and a small neutral medical plus.
    rect(im,20,20,2,10,p.coat);rect(im,26,20,2,10,p.coat);
    rect(im,20,24,3,5,p.coat);rect(im,25,24,3,5,p.coat);
    rect(im,20,26,2,1,'b7d8d1');rect(im,26,26,2,1,'b7d8d1');
    pixel(im,21,23,p.dark);rect(im,20,24,3,1,p.dark);pixel(im,21,25,p.dark);
    rect(im,16,20,4,10,'c1d9d2');rect(im,28,20,4,10,'dbe6dc');rect(im,32,20,8,10,'dce8df');
    rect(im,35,21,2,7,p.cloth);rect(im,33,23,6,2,p.cloth);
    // Teal scrub cap, with a pale tab centered above the forehead.
    rect(im,40,0,8,8,p.cloth);rect(im,32,8,32,2,p.cloth);rect(im,40,8,8,1,p.light);
    rect(im,43,9,2,1,p.coat);
    for(const [u,v] of [[40,16],[32,48]]) {rect(im,u+4,v+4,4,3,p.coat);rect(im,u+4,v+10,4,2,p.dark);}
  } else if(role==='engineer'){
    // Amber safety vest over navy workwear, reflective bands, pocket and tool belt.
    rect(im,23,20,2,10,p.pants);rect(im,20,23,8,1,p.accent);rect(im,20,27,8,1,p.accent);
    rect(im,21,21,1,7,p.accent);rect(im,26,21,1,7,p.accent);
    rect(im,25,24,2,2,p.dark);pixel(im,26,24,'dce4e9');
    rect(im,32,23,8,1,p.accent);rect(im,32,27,8,1,p.accent);
    rect(im,32,20,1,9,p.pants);rect(im,39,20,1,9,p.pants);
    rect(im,20,29,8,2,'66482d');rect(im,20,29,2,3,'a7763c');rect(im,26,29,2,2,'778b97');
    for(const [u,v] of [[40,16],[32,48]]){
      rect(im,u,v+4,16,8,p.pants);rect(im,u+4,v+5,4,1,p.accent);
      rect(im,u,v+13,16,3,'735f42');rect(im,u+4,v+14,4,1,'a28b64');
    }
    // Authored hard-hat shell on the outer head layer.
    rect(im,40,0,8,8,p.cloth);rect(im,42,0,2,8,p.light);
    rect(im,32,8,32,4,p.cloth);rect(im,32,11,32,1,p.dark);
    rect(im,40,8,8,1,p.light);rect(im,41,12,6,1,p.cloth);
    pixel(im,43,9,p.accent);pixel(im,44,9,p.accent);
  } else {
    // Cobalt courier jacket. A sand messenger bag has a diagonal front strap and back flap.
    rect(im,20,22,2,4,p.light);rect(im,26,22,2,4,p.dark);
    for(let row=0;row<10;row++) {const x=20+Math.min(7,Math.floor(row*.7));pixel(im,x,20+row,p.accent);if(x<27)pixel(im,x+1,20+row,'ae7d42');}
    rect(im,33,36,6,9,'9c6d3e');rect(im,33,36,6,3,p.accent);rect(im,34,39,4,5,'c39359');
    rect(im,35,39,2,1,'edd1a4');rect(im,35,42,2,2,'725138');pixel(im,36,42,'e7c686');
    rect(im,32,37,1,7,'785334');rect(im,39,37,1,7,'785334');
    rect(im,20,27,3,3,p.dark);rect(im,21,27,1,2,'b6ceec');
    rect(im,40,0,8,8,p.dark);rect(im,32,8,32,2,p.cloth);rect(im,40,9,8,2,p.dark);
    rect(im,43,8,2,1,p.accent);
    for(const [u,v] of [[40,16],[32,48]])rect(im,u+4,v+10,4,1,p.accent);
  }
  // Check all six opaque base cuboids. Unused UV space and garment overlays remain transparent.
  for(const [u,v,w,h,d] of [[0,0,8,8,8],[16,16,8,12,4],[40,16,4,12,4],[32,48,4,12,4],[0,16,4,12,4],[16,48,4,12,4]]){
    for(let y=v+d;y<v+d+h;y++)for(let x=u;x<u+2*(w+d);x++)assert.equal(im.pixels[(y*64+x)*4+3],255,`${role}: opaque base face`);
  }
  return im;
}
function crc32(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
function chunk(type,data){const t=Buffer.from(type),n=Buffer.alloc(4),c=Buffer.alloc(4);n.writeUInt32BE(data.length);c.writeUInt32BE(crc32(Buffer.concat([t,data])));return Buffer.concat([n,t,data,c]);}
function png(im){const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(im.w);ihdr.writeUInt32BE(im.h,4);ihdr[8]=8;ihdr[9]=6;
  const rows=[];for(let y=0;y<im.h;y++)rows.push(Buffer.from([0]),im.pixels.subarray(y*im.w*4,(y+1)*im.w*4));
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(Buffer.concat(rows))),chunk('IEND',Buffer.alloc(0))]);}
function blit(dst,src,sx,sy,w,h,dx,dy,scale=1){for(let y=0;y<h;y++)for(let x=0;x<w;x++){let c=src.pixels.subarray(((sy+y)*src.w+sx+x)*4,((sy+y)*src.w+sx+x)*4+4);if(c[3])rect(dst,dx+x*scale,dy+y*scale,scale,scale,c);}}
function figure(dst,skin,x,y,back){
  const s=6;const head=back?24:8,body=back?32:20,rarm=back?52:44,larm=back?44:36,rleg=back?12:4,lleg=back?28:20;
  blit(dst,skin,head,8,8,8,x+4*s,y,s);blit(dst,skin,head+32,8,8,8,x+4*s,y,s);
  blit(dst,skin,body,20,8,12,x+4*s,y+8*s,s);blit(dst,skin,body,36,8,12,x+4*s,y+8*s,s);
  blit(dst,skin,rarm,20,4,12,x,y+8*s,s);blit(dst,skin,larm,52,4,12,x+12*s,y+8*s,s);
  blit(dst,skin,rleg,20,4,12,x+4*s,y+20*s,s);blit(dst,skin,lleg,52,4,12,x+8*s,y+20*s,s);
}
fs.mkdirSync(output,{recursive:true});fs.mkdirSync(proof,{recursive:true});
const preview=canvas(792,264);rect(preview,0,0,preview.w,preview.h,'101c28');
let i=0;
for(const role of Object.keys(palette)){
  const skin=make(role),encoded=png(skin);assert.equal(encoded.readUInt32BE(16),64);assert.equal(encoded.readUInt32BE(20),64);
  fs.writeFileSync(path.join(output,`${role}.png`),encoded);
  const x=i*264;rect(preview,x+8,8,248,248,'1c2b39');rect(preview,x+8,8,248,5,palette[role].cloth);
  figure(preview,skin,x+24,36,false);figure(preview,skin,x+144,36,true);i++;
  console.log(`PASS ${role}.png: 64x64 RGBA; all base UV faces opaque; classic wide model; ${encoded.length} bytes`);
}
fs.writeFileSync(path.join(proof,'role-skins-preview.png'),png(preview));
console.log('Preview left to right: Medic teal/white, Engineer amber/navy, Courier cobalt with messenger bag; front and back each.');
