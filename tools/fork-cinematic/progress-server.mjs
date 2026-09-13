import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
if(process.env.COMPUTERNAME?.toUpperCase()!=='LAPTOP')throw Error('Laptop preview only');
const privateDir=path.join(root,'.work/fork/cinematic');
const routes={'/':['media/edit/progress.html','text/html; charset=utf-8'],'/title.png':['.work/fork/cinematic/title-internal-card-preview.png','image/png'],'/end.png':['.work/fork/cinematic/end-internal-card-preview.png','image/png']};
const recordFolder=path.join(process.env.LOCALAPPDATA,'FORK-Tools/preflight-20260913/obs');
const server=http.createServer((request,response)=>{
  response.setHeader('Cache-Control','no-store');
  if(request.method!=='GET'){response.writeHead(405).end();return;}
  if(request.url==='/review.json'||request.url==='/review.mp4'){
    const descriptor=path.join(privateDir,'current-review.json');
    const review=fs.existsSync(descriptor)?JSON.parse(fs.readFileSync(descriptor,'utf8').replace(/^\uFEFF/,'')):null;
    const file=review?.source?path.resolve(root,review.source):'';
    const outputRoot=path.resolve(root,'media/output')+path.sep;
    const ready=file.startsWith(outputRoot)&&path.extname(file)==='.mp4'&&fs.existsSync(file);
    if(request.url==='/review.json'){response.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({ready,label:review?.label||'Real recording pending',sha256:review?.sha256||null}));return;}
    if(!ready){response.writeHead(404).end();return;}
    const size=fs.statSync(file).size,range=request.headers.range;
    let start=0,end=size-1;
    if(range){const m=/^bytes=(\d+)-(\d*)$/.exec(range);if(!m){response.writeHead(416,{'Content-Range':`bytes */${size}`}).end();return;}start=Number(m[1]);end=m[2]?Math.min(Number(m[2]),size-1):size-1;if(start>end||start>=size){response.writeHead(416,{'Content-Range':`bytes */${size}`}).end();return;}}
    response.writeHead(range?206:200,{'Content-Type':'video/mp4','Accept-Ranges':'bytes','Content-Length':end-start+1,...(range?{'Content-Range':`bytes ${start}-${end}/${size}`}:{})});
    fs.createReadStream(file,{start,end}).on('error',()=>response.destroy()).pipe(response);return;
  }
  if(request.url==='/progress.json'){
    const status=JSON.parse(fs.readFileSync(path.join(privateDir,'status.json'),'utf8').replace(/^\uFEFF/,''));
    const recordings=[recordFolder,path.join(process.env.USERPROFILE,'Videos')].flatMap(folder=>fs.readdirSync(folder).filter(n=>/\.(mkv|mp4|mov)$/i.test(n)&&fs.statSync(path.join(folder,n)).mtimeMs>=Date.parse('2026-09-13T02:30:00Z')).map(name=>({name})));
    response.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({status,recordings}));return;
  }
  const route=routes[request.url];if(!route){response.writeHead(404).end();return;}
  response.writeHead(200,{'Content-Type':route[1]});fs.createReadStream(path.join(root,route[0])).pipe(response);
});
server.listen(8765,'127.0.0.1',()=>fs.writeFileSync(path.join(privateDir,'preview-server.json'),JSON.stringify({pid:process.pid,startedUtc:new Date().toISOString(),url:'http://127.0.0.1:8765/',kind:'Visible local progress page; no capture or render',stopDeadlineUtc:'2026-09-13T05:10:00Z'},null,2)));
setTimeout(()=>server.close(()=>process.exit(0)),Math.max(1,Date.parse('2026-09-13T05:10:00Z')-Date.now()));
