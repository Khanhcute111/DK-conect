const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const schema = fs.readFileSync(path.join(__dirname, "..", "schema.sql"), "utf8");
const app = fs.readFileSync(path.join(__dirname, "..", "src", "index.js"), "utf8");
const android = fs.readFileSync(path.join(__dirname, "..", "..", "android", "app", "src", "main", "java", "com", "dkconnect", "app", "MainActivity.kt"), "utf8");

test("database schema defines core entities and indexes", () => {
  for (const table of ["users", "friendships", "moments", "messages", "stories", "stickers"]) {
    assert.match(schema, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}`));
  }
  assert.match(schema, /idx_messages_pair_time/);
  assert.match(schema, /idx_moments_time/);
});

test("API includes health, auth, profile, moments and message routes", () => {
  for (const route of [
    'app.get("/health"',
    'app.post("/api/auth/register"',
    'app.post("/api/auth/login"',
    'app.get("/api/me"',
    'app.get("/api/moments"',
    'app.post("/api/moments"',
    'app.get("/api/messages/:userId"'
  ]) assert.ok(app.includes(route), `Missing ${route}`);
});

test("realtime server authenticates sockets and relays chat and call signaling", () => {
  assert.match(app, /io\.use\(/);
  assert.match(app, /message:send/);
  for (const event of ["call:offer", "call:answer", "call:ice", "call:hangup"]) {
    assert.ok(app.includes(event), `Missing ${event}`);
  }
});

test("Android app exposes the five main navigation destinations", () => {
  for (const label of ["Khoảnh khắc", "Tin nhắn", "Bạn bè", "Cuộc gọi", "Cá nhân"]) {
    assert.ok(android.includes(`"${label}"`), `Missing ${label}`);
  }
});

test("JWT secret is required and uploaded moments require HTTPS URLs", () => {
  assert.match(app, /JWT_SECRET must contain at least 32 characters/);
  assert.match(app, /https:\\\/\\\//);
});

test("admin routes enforce a database-backed role and expose moderation controls", () => {
  assert.match(schema, /role VARCHAR\(16\).*DEFAULT 'user'/);
  assert.match(app, /async function requireAdmin/);
  assert.match(app, /ADMIN_USERNAME/);
  assert.match(app, /ADMIN_PASSWORD/);
  assert.match(app, /LOWER\(username\)=LOWER\(\$1\)/);
  assert.match(app, /SELECT role FROM users WHERE id=\$1/);
  assert.match(app, /app\.get\("\/api\/admin\/commands", auth, requireAdmin/);
  assert.match(app, /app\.post\("\/api\/admin\/settings\/anti-recall", auth, requireAdmin/);
  assert.match(app, /app\.post\("\/api\/stickers"/);
  assert.match(app, /libwebp_anim/);
});

test("realtime presence and typing events are wired", () => {
  for (const event of ["presence:update", "typing:start", "typing:stop", "typing:update"]) {
    assert.ok(app.includes(event), `Missing ${event}`);
  }
});

test("Android includes animated navigation, themes, local chat history and typing UI", () => {
  for (const feature of ["AnimatedContent", "Tuỳ biến DK Connect", "Tím cực quang", "chat_messages", "Đang soạn tin nhắn", "Lưu lịch sử trò chuyện"]) {
    assert.ok(android.includes(feature), `Missing ${feature}`);
  }
});

test("chat endpoints enforce accepted friendships and support paginated durable history", () => {
  assert.match(app, /async function canChat/);
  assert.match(app, /FRIENDSHIP_REQUIRED/);
  assert.match(app, /nextBefore/);
  assert.match(app, /ORDER BY id DESC LIMIT \$4/);
  assert.match(app, /message:read/);
});

test("friend request and acceptance endpoints are present", () => {
  assert.match(app, /app\.get\("\/api\/friends"/);
  assert.match(app, /app\.post\("\/api\/friends\/request"/);
  assert.match(app, /app\.post\("\/api\/friends\/accept"/);
});

test("socket message delivery validates friendship and acknowledges persistence", () => {
  assert.match(app, /socket\.on\("message:send"/);
  assert.match(app, /ack\(\{ ok: true, message: rows\[0\] \}\)/);
  assert.match(app, /await canChat\(uid, receiverId\)/);
});

test("Android authentication no longer allows skipping the login form", () => {
  assert.match(android, /api\/auth\/login/);
  assert.match(android, /api\/auth\/register/);
  assert.match(android, /auth_token/);
  assert.doesNotMatch(android, /Đăng nhập · xem giao diện/);
});


test("friend discovery, 24-hour stories, media stickers and message recall routes exist", () => {
  for (const route of [
    'app.get("/api/users"',
    'app.get("/api/stories"',
    'app.post("/api/stories/upload"',
    'app.get("/api/stickers"',
    'app.post("/api/stickers"',
    'app.post("/api/messages/:messageId/recall"'
  ]) assert.ok(app.includes(route), `Missing ${route}`);
  assert.match(app, /NOW\(\)\+INTERVAL '24 hours'/);
  assert.match(app, /libwebp_anim/);
  assert.match(app, /RECALL_DISABLED_BY_ADMIN/);
});

test("Android friends and messages screens call real API and realtime client", () => {
  assert.match(android, /RemoteApi\(token, baseUrl\)/);
  assert.match(android, /RealtimeClient/);
  assert.match(android, /api\.friends\(\)/);
  assert.match(android, /api\.messagePage\(peer\.id\)/);
  assert.match(android, /realtime\.send/);
  assert.match(android, /uploadSticker/);
  assert.match(android, /uploadStory/);
});


test("chat send uses idempotency keys for safe realtime-to-REST fallback", () => {
  assert.match(schema, /client_id UUID/);
  assert.match(schema, /idx_messages_sender_client/);
  assert.match(app, /ON CONFLICT\(sender_id,client_id\) WHERE client_id IS NOT NULL/);
  assert.match(android, /UUID\.randomUUID\(\)/);
  assert.match(android, /withTimeout\(10_000\)/);
});


test("media conversion separates still-image and animated-video pipelines", () => {
  assert.match(app, /const stillFilter = "scale=/);
  assert.match(app, /const animatedFilter = `fps=12,/);
  assert.match(app, /activeStickerJobs >= 2/);
  assert.match(app, /LIMIT_FILE_SIZE/);
});

test("Android supports configurable API hosts and paginated chat history", () => {
  assert.match(android, /api_base_url/);
  assert.match(android, /Địa chỉ máy chủ API/);
  assert.match(android, /api\.messagePage\(peer\.id/);
  assert.match(android, /snapshotFlow/);
  assert.match(android, /ImageDecoderDecoder/);
});
