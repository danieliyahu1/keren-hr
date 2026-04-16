// fetch-verification-code.js
// Fetches the most recent 6-digit verification code from the Gmail INBOX.
// Usage: node /scripts/fetch-verification-code.js
// Prints a single 6-digit code to stdout. Exits with code 1 on failure.

'use strict';

const imaps = require('imap-simple');

const config = {
  imap: {
    user: 'kerenbrown43@gmail.com',
    password: 'girkiqblkkmlgrkf',
    host: 'imap.gmail.com',
    port: 993,
    tls: true,
    tlsOptions: { rejectUnauthorized: false },
    authTimeout: 10000
  }
};

imaps.connect(config).then(connection => {
  return connection.openBox('INBOX').then(() => {
    return connection.search(['ALL'], { bodies: ['TEXT'], markSeen: false });
  }).then(messages => {
    connection.end();
    if (!messages.length) {
      console.error('No messages in inbox.');
      process.exit(1);
    }
    // Search from most recent backwards for the first 6-digit code
    for (let i = messages.length - 1; i >= 0; i--) {
      const body = messages[i].parts[0].body;
      const match = body.match(/\b\d{6}\b/);
      if (match) {
        console.log(match[0]);
        process.exit(0);
      }
    }
    console.error('No 6-digit code found in inbox.');
    process.exit(1);
  });
}).catch(err => {
  console.error('IMAP error: ' + err.message);
  process.exit(1);
});
