'use strict';
const fs = require('node:fs');
const path = require('node:path');
const http2 = require('node:http2');

function emulatorRegistration(serial) {
  const serialPort = /^emulator-(\d{4,5})$/.exec(serial)?.[1];
  if (!serialPort) throw Error('invalid emulator serial');
  const folder = path.join(process.env.LOCALAPPDATA, 'Temp', 'avd', 'running');
  const entries = fs.readdirSync(folder, {withFileTypes:true})
    .filter(entry => entry.isFile() && /^pid_\d+\.ini$/.test(entry.name));
  for (const entry of entries) {
    const ini = fs.readFileSync(path.join(folder,entry.name),'utf8');
    const pairs = ini.split(/\r?\n/).map(line=>line.match(/^([\w.]+)\s*=\s*(.*)$/)).filter(Boolean);
    const data = new Map(pairs.map(([,key,value])=>[key,value.trim()]));
    if (data.get('port.serial') !== serialPort) continue;
    const port = Number(data.get('grpc.port'));
    const token = data.get('grpc.token');
    if (!Number.isInteger(port) || port < 1024 || port > 65535 || !token) continue;
    return {port, token};
  }
  throw Error('emulator registration unavailable');
}

function varint(number) {
  const bytes = [];
  while (number >= 128) { bytes.push((number & 127) | 128); number >>>= 7; }
  bytes.push(number);
  return Buffer.from(bytes);
}

async function setClipboard(serial, text) {
  const {port,token} = emulatorRegistration(serial);
  const body = Buffer.from(text, 'utf8');
  if (!body.length || body.length > 500) throw Error('search query length out of range');
  const message = Buffer.concat([Buffer.from([10]),varint(body.length),body]);
  const prefix = Buffer.alloc(5);
  prefix.writeUInt32BE(message.length,1);
  const client = http2.connect('http://127.0.0.1:'+port);
  try {
    await new Promise((resolve,reject)=>{
      let status, http;
      const req = client.request({
        ':method':'POST',
        ':path':'/android.emulation.control.EmulatorController/setClipboard',
        'content-type':'application/grpc',
        te:'trailers',
        authorization:'Bearer '+token,
      });
      req.setTimeout(4500,()=>{req.destroy(); reject(Error('emulator clipboard timed out'));});
      req.on('response',(headers)=>{http=headers[':status']; status=headers['grpc-status'];});
      req.on('trailers',(headers)=>{status=headers['grpc-status'];});
      req.on('error',()=>reject(Error('emulator clipboard transport failed')));
      client.on('error',()=>reject(Error('emulator control connection failed')));
      req.on('data',()=>{});
      req.on('end',()=>http===200 && status==='0' ? resolve() : reject(Error('emulator clipboard update not confirmed')));
      req.end(Buffer.concat([prefix,message]));
    });
  } finally {
    client.destroy();
  }
}

if (require.main===module) {
  const [, , serial, ...args] = process.argv;
  setClipboard(serial,args.join(' ')).catch(()=>{
    process.stderr.write('Unable to update emulator clipboard.\n');
    process.exitCode=1;
  });
}

