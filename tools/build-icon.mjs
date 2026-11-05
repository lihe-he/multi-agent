import { writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
const size = 256;
const pixels = Buffer.alloc((size * 4 + 1) * size);
for (let py = 0; py < size; py++) for (let px = 0; px < size; px++) {
  const x = px * 64 / size, y = py * 64 / size;
  const i = py * (size * 4 + 1) + 1 + px * 4;
  const corner = (x < 12 && y < 12 && Math.hypot(x - 12, y - 12) > 12) || (x > 51 && y < 12 && Math.hypot(x - 51, y - 12) > 12) || (x < 12 && y > 51 && Math.hypot(x - 12, y - 51) > 12) || (x > 51 && y > 51 && Math.hypot(x - 51, y - 51) > 12);
  const dot = [[20, 22], [44, 22], [32, 43]].some(([cx, cy]) => Math.hypot(x - cx, y - cy) <= 6);
  const line = (x >= 20 && x <= 44 && Math.abs(y - 22) < 2) || (y >= 22 && y <= 43 && (Math.abs(x - (20 + (y - 22) * 12 / 21)) < 2 || Math.abs(x - (44 - (y - 22) * 12 / 21)) < 2));
  pixels[i] = dot || line ? 234 : 12; pixels[i + 1] = dot || line ? 255 : 129 + Math.floor(y / 4); pixels[i + 2] = dot || line ? 247 : 104; pixels[i + 3] = corner ? 0 : 255;
}
function crc32(data) { let crc = -1; for (const byte of data) { crc ^= byte; for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); } return (crc ^ -1) >>> 0; }
function chunk(type, payload) { const kind = Buffer.from(type); const out = Buffer.alloc(payload.length + 12); out.writeUInt32BE(payload.length); kind.copy(out, 4); payload.copy(out, 8); out.writeUInt32BE(crc32(Buffer.concat([kind, payload])), payload.length + 8); return out; }
const header = Buffer.alloc(13); header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
const png = Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
writeFileSync(new URL('../desktop/icon.png', import.meta.url), png);
const ico = Buffer.alloc(22); ico.writeUInt16LE(1, 2); ico.writeUInt16LE(1, 4); ico.writeUInt16LE(1, 10); ico.writeUInt16LE(32, 12); ico.writeUInt32LE(png.length, 14); ico.writeUInt32LE(22, 18);
writeFileSync(new URL('../desktop/icon.ico', import.meta.url), Buffer.concat([ico, png]));
