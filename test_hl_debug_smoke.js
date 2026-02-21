#!/usr/bin/env node
'use strict';

const { spawn } = require('child_process');
const { dirname, resolve } = require('path');

// ---------------------------------------------------------------------------
// CONFIG — positional argv: hlPath adapterPath nodePath programPath sourcePath
//          breakpointFile breakpointLine debugPort timeoutMs
// ---------------------------------------------------------------------------
const CONFIG = {
  hlPath:          process.argv[2] || 'hl',
  adapterPath:     process.argv[3] || '',
  nodePath:        process.argv[4] || 'node',
  programPath:     process.argv[5] || '',
  sourcePath:      process.argv[6] || '',
  breakpointFile:  process.argv[7] || '',
  breakpointLine:  parseInt(process.argv[8]) || 1,
  debugPort:       parseInt(process.argv[9]) || 6112,
  timeoutMs:       parseInt(process.argv[10]) || 10000,
};

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------
if (!CONFIG.adapterPath) {
  process.stdout.write('[FAIL] CONFIG.adapterPath is required\n');
  process.exit(1);
}
if (!CONFIG.programPath) {
  process.stdout.write('[FAIL] CONFIG.programPath is required\n');
  process.exit(1);
}
if (!CONFIG.breakpointFile) {
  process.stdout.write('[FAIL] CONFIG.breakpointFile is required\n');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Logging helpers
// ---------------------------------------------------------------------------
function info(msg)    { process.stdout.write(`[INFO] ${msg}\n`); }
function dapSent(cmd) { process.stdout.write(`[DAP →] ${cmd}\n`); }
function dapRecv(msg) {
  if (msg.type === 'event') {
    process.stdout.write(`[DAP ←] type=event event=${msg.event}\n`);
  } else if (msg.type === 'response') {
    process.stdout.write(`[DAP ←] type=response command=${msg.command} success=${msg.success}\n`);
  } else {
    process.stdout.write(`[DAP ←] type=${msg.type}\n`);
  }
}
function pass(msg) { process.stdout.write(`[PASS] ${msg}\n`); }
function fail(msg) { process.stdout.write(`[FAIL] ${msg}\n`); }

// ---------------------------------------------------------------------------
// sendDAP helper
// ---------------------------------------------------------------------------
let seqCounter = 0;

function sendDAP(proc, obj) {
  if (!obj.seq) obj.seq = ++seqCounter;
  if (!obj.type) obj.type = 'request';
  const json = JSON.stringify(obj);
  const len = Buffer.byteLength(json, 'utf8');
  proc.stdin.write(`Content-Length: ${len}\r\n\r\n${json}`);
  dapSent(obj.command || obj.type);
}

// ---------------------------------------------------------------------------
// State tracking
// ---------------------------------------------------------------------------
let hlProc = null;
let adapterProc = null;
let globalTimer = null;
let stoppedReceived = false;
let disconnectSent = false;

// ---------------------------------------------------------------------------
// Cleanup helper
// ---------------------------------------------------------------------------
function cleanup(exitCode) {
  if (globalTimer) {
    clearTimeout(globalTimer);
    globalTimer = null;
  }
  try { if (adapterProc && !adapterProc.killed) adapterProc.kill(); } catch (_) {}
  try { if (hlProc && !hlProc.killed) hlProc.kill(); } catch (_) {}
  process.exit(exitCode);
}

// ---------------------------------------------------------------------------
// Spawn HL
// ---------------------------------------------------------------------------
info(`Spawning HL: ${CONFIG.hlPath} --debug ${CONFIG.debugPort} --debug-wait ${CONFIG.programPath}`);
const programDir = dirname(resolve(CONFIG.programPath));

hlProc = spawn(CONFIG.hlPath, [
  '--debug', String(CONFIG.debugPort), '--debug-wait', CONFIG.programPath
], {
  cwd: programDir,
  stdio: ['ignore', 'pipe', 'pipe'],
});

hlProc.stdout.on('data', (d) => {
  for (const line of d.toString().split('\n').filter(Boolean)) {
    process.stdout.write(`[HL OUT] ${line}\n`);
  }
});
hlProc.stderr.on('data', (d) => {
  for (const line of d.toString().split('\n').filter(Boolean)) {
    process.stdout.write(`[HL ERR] ${line}\n`);
  }
});
hlProc.on('error', (err) => {
  fail(`HL process error: ${err.message}`);
  cleanup(1);
});

// ---------------------------------------------------------------------------
// Content-Length framing parser
// ---------------------------------------------------------------------------
let buf = Buffer.alloc(0);
const HEADER_SEP = Buffer.from('\r\n\r\n');

function parseFrames(chunk) {
  buf = Buffer.concat([buf, chunk]);

  while (true) {
    const sepIdx = buf.indexOf(HEADER_SEP);
    if (sepIdx === -1) break;

    const headerStr = buf.slice(0, sepIdx).toString('utf8');
    const match = headerStr.match(/Content-Length:\s*(\d+)/i);
    if (!match) {
      fail('Malformed DAP header: ' + headerStr);
      cleanup(1);
      return;
    }

    const contentLength = parseInt(match[1], 10);
    const bodyStart = sepIdx + 4;

    if (buf.length < bodyStart + contentLength) break; // incomplete body

    const bodyBuf = buf.slice(bodyStart, bodyStart + contentLength);
    buf = buf.slice(bodyStart + contentLength);

    let msg;
    try {
      msg = JSON.parse(bodyBuf.toString('utf8'));
    } catch (e) {
      fail('Malformed DAP JSON: ' + e.message);
      cleanup(1);
      return;
    }

    handleMessage(msg);
  }
}

// ---------------------------------------------------------------------------
// DAP state machine — handleMessage
// ---------------------------------------------------------------------------
function handleMessage(msg) {
  dapRecv(msg);

  // --- Responses ---
  if (msg.type === 'response') {
    switch (msg.command) {
      case 'initialize':
        if (!msg.success) {
          fail('initialize failed: ' + (msg.message || 'unknown'));
          cleanup(1);
          return;
        }
        info('Initialize succeeded — sending attach');
        const attachArgs = {
          port: CONFIG.debugPort,
          program: CONFIG.programPath,
          cwd: programDir,
          classPaths: CONFIG.sourcePath ? [CONFIG.sourcePath] : [],
        };
        sendDAP(adapterProc, { command: 'attach', arguments: attachArgs });
        break;

      case 'attach':
        if (!msg.success) {
          fail('attach failed: ' + (msg.message || 'unknown'));
          cleanup(1);
          return;
        }
        info('Attach succeeded');
        if (CONFIG.breakpointFile) {
          info('Setting breakpoint at ' + CONFIG.breakpointFile + ':' + CONFIG.breakpointLine);
          sendDAP(adapterProc, {
            command: 'setBreakpoints',
            arguments: {
              source: { path: CONFIG.breakpointFile },
              breakpoints: [{ line: CONFIG.breakpointLine }],
            },
          });
        } else {
          info('No breakpoint configured — sending configurationDone');
          sendDAP(adapterProc, { command: 'configurationDone' });
        }
        break;

      case 'setBreakpoints':
        info('Breakpoints set — sending configurationDone');
        sendDAP(adapterProc, { command: 'configurationDone' });
        break;

      case 'disconnect':
        pass('Disconnect clean');
        cleanup(0);
        return;

      default:
        break;
    }
  }

  // --- Events ---
  if (msg.type === 'event') {
    switch (msg.event) {
      case 'stopped':
        stoppedReceived = true;
        if (globalTimer) {
          clearTimeout(globalTimer);
          globalTimer = null;
        }
        pass('Breakpoint hit — stopped event received');
        if (!disconnectSent) {
          disconnectSent = true;
          sendDAP(adapterProc, {
            command: 'disconnect',
            arguments: { terminateDebuggee: true },
          });
        }
        break;

      case 'terminated':
        if (!stoppedReceived) {
          fail('Adapter terminated before stopped event');
          cleanup(1);
        }
        break;

      default:
        break;
    }
  }
}

// ---------------------------------------------------------------------------
// Spawn adapter after startup delay
// ---------------------------------------------------------------------------
setTimeout(() => {
  info(`Spawning adapter: ${CONFIG.nodePath} ${CONFIG.adapterPath}`);

  adapterProc = spawn(CONFIG.nodePath, [CONFIG.adapterPath], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  adapterProc.stderr.on('data', (d) => {
    for (const line of d.toString().split('\n').filter(Boolean)) {
      process.stdout.write(`[ADAPTER ERR] ${line}\n`);
    }
  });

  adapterProc.on('error', (err) => {
    fail(`Adapter process error: ${err.message}`);
    cleanup(1);
  });

  adapterProc.on('close', (code) => {
    if (!stoppedReceived) {
      fail(`Adapter exited (code ${code}) before stopped event`);
      cleanup(1);
    }
  });

  // Attach framing parser to adapter stdout
  adapterProc.stdout.on('data', (chunk) => parseFrames(chunk));

  // Start global timeout
  globalTimer = setTimeout(() => {
    fail(`Global timeout (${CONFIG.timeoutMs}ms) exceeded`);
    cleanup(1);
  }, CONFIG.timeoutMs);

  // Send initialize request (seq 1)
  info('Sending initialize');
  sendDAP(adapterProc, {
    command: 'initialize',
    arguments: {
      adapterID: 'hashlink',
      clientID: 'smoke-test',
      linesStartAt1: true,
      columnsStartAt1: true,
      pathFormat: 'path',
    },
  });
}, 500);
