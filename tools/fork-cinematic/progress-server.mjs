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
  if(request.url==='/progress.json'){
    const status=JSON.parse(fs.readFileSync(path.join(privateDir,'status.json'),'utf8').replace(/^\uFEFF/,''));
    const recordings=fs.readdirSync(recordFolder).filter(n=>/\.(mkv|mp4|mov)$/i.test(n)).map(name=>({name}));
    response.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({status,recordings}));return;
  }
  const route=routes[request.url];if(!route){response.writeHead(404).end();return;}
  response.writeHead(200,{'Content-Type':route[1]});fs.createReadStream(path.join(root,route[0])).pipe(response);
});
server.listen(8765,'127.0.0.1',()=>fs.writeFileSync(path.join(privateDir,'preview-server.json'),JSON.stringify({pid:process.pid,startedUtc:new Date().toISOString(),url:'http://127.0.0.1:8765/',kind:'Visible local progress page; no capture or render',stopDeadlineUtc:'2026-09-13T05:10:00Z'},null,2)));
setTimeout(()=>server.close(()=>process.exit(0)),Math.max(1,Date.parse('2026-09-13T05:10:00Z')-Date.now()));
