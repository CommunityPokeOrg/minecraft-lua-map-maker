/** NDJSON framing tests — LineFramer + encodeRequest, no sockets needed. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { encodeRequest, LineFramer } from '../src/luabridge';

test('encodeRequest emits one v1 ndjson line', () => {
    const line = encodeRequest({ v: 1, id: 7, op: 'status' });
    assert.equal(line, '{"v":1,"id":7,"op":"status"}\n');
});

test('encodeRequest includes optional code/name only when set', () => {
    assert.equal(
        encodeRequest({ v: 1, id: 1, op: 'eval', code: 'return 1' }),
        '{"v":1,"id":1,"op":"eval","code":"return 1"}\n');
    assert.equal(
        encodeRequest({ v: 1, id: 2, op: 'run', name: 'hello' }),
        '{"v":1,"id":2,"op":"run","name":"hello"}\n');
});

test('framer decodes one reply per line', () => {
    const f = new LineFramer();
    const out = f.push(
        '{"v":1,"id":1,"ok":true,"result":"a"}\n' +
        '{"v":1,"id":2,"ok":false,"error":"boom"}\n');
    assert.equal(out.length, 2);
    assert.equal(out[0].id, 1);
    assert.equal(out[0].ok, true);
    assert.equal(out[1].ok, false);
    assert.equal(out[1].error, 'boom');
});

test('framer buffers partial lines and splits coalesced chunks', () => {
    const f = new LineFramer();
    assert.deepEqual(f.push('{"v":1,"id":1,"ok":tru'), []);
    assert.equal(f.pending, 22);
    const out = f.push('e}\n{"v":1,"id":2,"ok":true}\n{"v":1');
    assert.equal(out.length, 2);
    assert.equal(f.pending, 6);
});

test('framer skips blank lines', () => {
    const f = new LineFramer();
    const out = f.push('\n\n{"v":1,"id":9,"ok":true}\n\n');
    assert.equal(out.length, 1);
    assert.equal(out[0].id, 9);
});

test('framer throws on malformed json', () => {
    const f = new LineFramer();
    assert.throws(() => f.push('not json\n'));
});
