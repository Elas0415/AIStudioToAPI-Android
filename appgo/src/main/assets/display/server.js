// Minimal display gateway for the in-app login window.
//
// Mirrors iBUHub/AIStudioToAPI: Xvfb renders the screen, x11vnc exposes it as
// RFB, websockify turns that into a WebSocket and this process serves the noVNC
// client and proxies /vnc to websockify. Everything stays on loopback.
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.DISPLAY_PORT || 6081);
const WS_HOST = process.env.WEBSOCKIFY_HOST || '127.0.0.1';
const WS_PORT = Number(process.env.WEBSOCKIFY_PORT || 6080);
const ROOT = path.join(__dirname, 'web');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

function send(res, status, body, type) {
  res.writeHead(status, {
    'Content-Type': type || 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
  } catch (e) {
    send(res, 400, 'bad request');
    return;
  }
  if (pathname === '/') pathname = '/login.html';
  if (pathname === '/healthz') { send(res, 200, 'ok'); return; }

  const file = path.join(ROOT, path.normalize(pathname).replace(/^(\.\.[/\\])+/, ''));
  if (!file.startsWith(ROOT)) { send(res, 403, 'forbidden'); return; }
  fs.readFile(file, (err, data) => {
    if (err) { send(res, 404, 'not found'); return; }
    send(res, 200, data, TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream');
  });
});

// Same shape as ToAPI's /vnc handler: forward the WebSocket handshake verbatim
// and then pipe bytes. websockify does not care about the path.
server.on('upgrade', (req, socket) => {
  let pathname = '';
  try { pathname = new URL(req.url, 'http://127.0.0.1').pathname; } catch (e) {}
  if (pathname !== '/vnc' && pathname !== '/websockify') { socket.destroy(); return; }

  const target = net.connect(WS_PORT, WS_HOST, () => {
    const headers = [
      'GET ' + req.url + ' HTTP/1.1',
      'Host: ' + WS_HOST + ':' + WS_PORT,
      'Upgrade: websocket',
      'Connection: Upgrade',
      'Sec-WebSocket-Key: ' + req.headers['sec-websocket-key'],
      'Sec-WebSocket-Version: ' + req.headers['sec-websocket-version'],
    ];
    if (req.headers['sec-websocket-protocol']) {
      headers.push('Sec-WebSocket-Protocol: ' + req.headers['sec-websocket-protocol']);
    }
    if (req.headers['sec-websocket-extensions']) {
      headers.push('Sec-WebSocket-Extensions: ' + req.headers['sec-websocket-extensions']);
    }
    target.write(headers.join('\r\n') + '\r\n\r\n');
    target.pipe(socket).pipe(target);
  });
  target.on('error', () => socket.destroy());
  socket.on('error', () => target.destroy());
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('[display] client + ws gateway on 127.0.0.1:' + PORT + ' -> ' + WS_HOST + ':' + WS_PORT);
});
