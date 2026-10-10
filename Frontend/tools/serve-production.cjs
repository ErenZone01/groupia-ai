const http = require('node:http'),
  fs = require('node:fs'),
  path = require('node:path');
const crypto = require('node:crypto');
const securityHeaders = require('./security-headers.cjs');
const root = path.resolve(__dirname, '../dist/groupia/browser');
const mime = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
};
http
  .createServer((req, res) => {
    const nonce = crypto.randomBytes(24).toString('base64');
    for (const [name, value] of Object.entries(
      securityHeaders(nonce, process.env.GROUPIA_HTTPS === 'true'),
    ))
      res.setHeader(name, value);
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD' });
      return res.end();
    }
    let pathname;
    try {
      pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    } catch {
      res.writeHead(400);
      return res.end();
    }
    let file = path.resolve(root, '.' + pathname);
    if (!file.startsWith(root + path.sep) && file !== root) {
      res.writeHead(403);
      return res.end();
    }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      if ((!pathname.startsWith('/s/') && path.extname(pathname)) || pathname.startsWith('/api/')) {
        res.writeHead(404);
        return res.end();
      }
      file = path.join(root, 'index.html');
    }
    res.setHeader('Referrer-Policy', 'no-referrer');
    if (pathname.startsWith('/s/')) res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
    if (path.extname(file) === '.html') {
      res.setHeader('Cache-Control', 'no-store');
      const html = fs
        .readFileSync(file, 'utf8')
        .replace('<app-root>', '<app-root ngCspNonce="' + nonce + '">');
      return res.end(req.method === 'HEAD' ? undefined : html);
    }
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).pipe(res);
  })
  .listen(Number(process.env.PORT || 4300), process.env.HOST || '127.0.0.1');
