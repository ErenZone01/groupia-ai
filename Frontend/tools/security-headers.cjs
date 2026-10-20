// HTTP headers for the compiled application served behind the production TLS proxy.
// The nonce is fresh per HTML response; never cache/reuse the generated HTML at the edge.
module.exports = function securityHeaders(nonce, https, mediaBaseUrl = process.env.GROUPIA_MEDIA_PUBLIC_BASE_URL || '') {
  let mediaOrigin = '';
  if (mediaBaseUrl) {
    const url = new URL(mediaBaseUrl);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.hostname.includes('*'))
      throw new Error('Invalid media public URL');
    mediaOrigin = ' ' + url.origin;
  }
  const headers = {
    'Content-Security-Policy': [
      "default-src 'self'",
      "script-src 'self'",
      `style-src 'self' 'nonce-${nonce}'`,
      // Angular's dynamic chart dimensions are style attributes; scripts have no inline exception.
      "style-src-attr 'unsafe-inline'",
      "img-src 'self' data: blob:" + mediaOrigin,
      "font-src 'self'",
      "connect-src 'self'",
      "frame-ancestors 'none'",
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      'trusted-types angular angular#bundler',
      "require-trusted-types-for 'script'",
    ].join('; '),
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  };
  if (https) headers['Strict-Transport-Security'] = 'max-age=31536000';
  return headers;
};
