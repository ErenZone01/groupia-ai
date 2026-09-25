const http = require('node:http'),
  fs = require('node:fs'),
  path = require('node:path');
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
      if (path.extname(pathname) || pathname.startsWith('/api/')) {
        res.writeHead(404);
        return res.end();
      }
      file = path.join(root, 'index.html');
    }
    res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
    fs.createReadStream(file).pipe(res);
  })
  .listen(4300, '127.0.0.1');
