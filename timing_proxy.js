// 链路计时代理 — 部署在车机上，记录 Gateway ↔ Z.AI 每步耗时
// 用法: 车机上 node-termux timing_proxy.js
// 然后修改 models.json 的 baseUrl 指向 http://127.0.0.1:8099

const http = require('http');
const https = require('https');
const fs = require('fs');
const LOG = '/data/local/tmp/timing_proxy.log';

function log(msg) {
    const line = new Date().toISOString().substr(11, 12) + ' ' + msg;
    fs.appendFileSync(LOG, line + '\n');
    console.log(line);
}

// 上游真实 API
const UPSTREAM_HOST = 'open.bigmodel.cn';
const UPSTREAM_PATH = '/api/paas/v4/chat/completions';

const server = http.createServer((req, res) => {
    if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }

    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
        const t0 = Date.now();
        const msg = JSON.parse(body);
        const userMsg = msg.messages?.[msg.messages.length-1]?.content?.substring(0, 50) || '';
        const sysLen = JSON.stringify(msg.messages?.filter(m => m.role === 'system') || []).length;
        const totalLen = body.length;

        log(`REQ  sys=${sysLen}B total=${totalLen}B  msg="${userMsg}"`);

        const proxyReq = https.request({
            hostname: UPSTREAM_HOST,
            path: UPSTREAM_PATH,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': req.headers['authorization'] || '',
                'Content-Length': Buffer.byteLength(body)
            },
            rejectUnauthorized: false
        }, proxyRes => {
            const t1 = Date.now();
            let respBody = '';
            proxyRes.on('data', c => respBody += c);
            proxyRes.on('end', () => {
                const t2 = Date.now();
                const tlsTime = t1 - t0;
                const inferTime = t2 - t1;
                const totalTime = t2 - t0;

                let outLen = 0;
                try {
                    const r = JSON.parse(respBody);
                    outLen = r.choices?.[0]?.message?.content?.length || 0;
                } catch(e) {}

                log(`RESP status=${proxyRes.statusCode}  TLS=${tlsTime}ms  infer=${inferTime}ms  total=${totalTime}ms  out=${outLen}chars`);
                res.writeHead(proxyRes.statusCode, proxyRes.headers);
                res.end(respBody);
            });
        });
        proxyReq.on('error', e => {
            log(`ERR ${e.message}`);
            res.writeHead(502);
            res.end(JSON.stringify({error: e.message}));
        });
        proxyReq.write(body);
        proxyReq.end();
    });
});

process.on('SIGTERM', () => { log('proxy stopped'); process.exit(0); });
process.on('SIGINT', () => { log('proxy stopped'); process.exit(0); });

server.listen(8099, '127.0.0.1', () => {
    log('timing proxy started on 127.0.0.1:8099 -> ' + UPSTREAM_HOST);
});
