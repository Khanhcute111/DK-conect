require("dotenv").config();
const express = require("express");
const cors = require("cors");
const http = require("http");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const { Pool } = require("pg");
const { Server } = require("socket.io");
const multer = require("multer");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { spawn } = require("node:child_process");

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: process.env.CORS_ORIGIN || "*" } });
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const PORT = Number(process.env.PORT || 3000);
if (!process.env.JWT_SECRET || process.env.JWT_SECRET.length < 32) throw new Error("JWT_SECRET must contain at least 32 characters");

app.use(cors({ origin: process.env.CORS_ORIGIN || "*" }));
app.use(express.json({ limit: "2mb" }));
app.disable("x-powered-by");

// Provision a dedicated administrator only from private server environment variables.
async function ensureBootstrapAdmin() {
  const username = String(process.env.ADMIN_USERNAME || "").trim();
  const password = String(process.env.ADMIN_PASSWORD || "");
  if (!username && !password) return;
  if (!/^[A-Za-z0-9_]{3,32}$/.test(username) || password.length < 8 || password.length > 72) {
    throw new Error("ADMIN_USERNAME must be 3-32 letters/numbers/underscores and ADMIN_PASSWORD 8-72 characters");
  }
  await pool.query("ALTER TABLE users ADD COLUMN IF NOT EXISTS username VARCHAR(32)");
  await pool.query("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_unique ON users(LOWER(username)) WHERE username IS NOT NULL");
  const hash = await bcrypt.hash(password, 12);
  const email = username.toLowerCase() + "@admin.dkconnect.local";
  const found = await pool.query("SELECT id FROM users WHERE LOWER(username)=LOWER($1) OR email=$2 LIMIT 1", [username, email]);
  if (found.rows[0]) {
    await pool.query("UPDATE users SET username=$1,display_name=$1,email=$2,password_hash=$3,role='admin' WHERE id=$4", [username, email, hash, found.rows[0].id]);
  } else {
    await pool.query("INSERT INTO users(username,display_name,email,password_hash,role) VALUES($1,$1,$2,$3,'admin')", [username, email, hash]);
  }
  console.log("Bootstrap admin account ready: " + username);
}
app.set("trust proxy", 1);

const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, "..", "uploads");
fs.mkdirSync(UPLOAD_DIR, { recursive: true });
app.use("/media", express.static(UPLOAD_DIR, { maxAge: "7d", immutable: true }));
const acceptedMime = new Set(["image/jpeg", "image/png", "image/webp", "video/mp4", "video/quicktime", "video/webm"]);
let activeStickerJobs = 0;
const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, UPLOAD_DIR),
    filename: (_req, file, cb) => {
      const ext = ({ "image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "video/mp4": ".mp4", "video/quicktime": ".mov", "video/webm": ".webm" })[file.mimetype];
      cb(null, `${crypto.randomUUID()}${ext || ".bin"}`);
    }
  }),
  limits: { fileSize: 25 * 1024 * 1024, files: 1 },
  fileFilter: (_req, file, cb) => acceptedMime.has(file.mimetype) ? cb(null, true) : cb(new Error("UNSUPPORTED_MEDIA_TYPE"))
});
function publicMediaUrl(req, filename) {
  const base = (process.env.PUBLIC_BASE_URL || `${req.protocol}://${req.get("host")}`).replace(/\/$/, "");
  return `${base}/media/${encodeURIComponent(filename)}`;
}
function runFfmpeg(args) {
  return new Promise((resolve, reject) => {
    const child = spawn("ffmpeg", args, { stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    const timer = setTimeout(() => { child.kill("SIGKILL"); reject(new Error("MEDIA_PROCESS_TIMEOUT")); }, 25_000);
    child.stderr.on("data", chunk => { stderr = (stderr + chunk.toString()).slice(-4000); });
    child.on("error", error => { clearTimeout(timer); reject(error); });
    child.on("close", code => { clearTimeout(timer); if (code === 0) resolve(); else reject(new Error(stderr.includes("Invalid") ? "INVALID_MEDIA" : "MEDIA_PROCESS_FAILED")); });
  });
}

// Small per-process limiter. Use a shared Redis limiter when deploying multiple instances.
const attempts = new Map();
function rateLimit({ windowMs = 60_000, max = 30 } = {}) {
  return (req, res, next) => {
    const key = `${req.ip}:${req.path}`;
    const now = Date.now();
    const current = attempts.get(key);
    if (!current || current.until <= now) {
      attempts.set(key, { count: 1, until: now + windowMs });
      return next();
    }
    current.count += 1;
    if (current.count > max) return res.status(429).json({ error: "TOO_MANY_REQUESTS" });
    next();
  };
}

function signToken(user) {
  return jwt.sign({ sub: String(user.id) }, process.env.JWT_SECRET, { expiresIn: "14d", issuer: "dkconnect", audience: "dkconnect-app" });
}
function auth(req, res, next) {
  const token = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  try {
    req.user = jwt.verify(token, process.env.JWT_SECRET, { issuer: "dkconnect", audience: "dkconnect-app" });
    next();
  } catch { res.status(401).json({ error: "UNAUTHORIZED" }); }
}
async function requireAdmin(req, res, next) {
  try {
    const { rows } = await pool.query("SELECT role FROM users WHERE id=$1", [req.user.sub]);
    if (rows[0]?.role !== "admin") return res.status(403).json({ error: "ADMIN_ONLY" });
    next();
  } catch { res.status(500).json({ error: "ADMIN_CHECK_FAILED" }); }
}
async function canChat(a, b) {
  const { rows } = await pool.query(
    `SELECT 1 FROM friendships WHERE status='accepted'
     AND ((user_id=$1 AND friend_id=$2) OR (user_id=$2 AND friend_id=$1)) LIMIT 1`, [a, b]);
  return rows.length > 0;
}
function validId(value) { return /^\d+$/.test(String(value || "")) && Number(value) > 0; }

app.get("/health", async (_req, res) => {
  try { await pool.query("SELECT 1"); res.json({ ok: true, service: "dkconnect-api", database: "connected" }); }
  catch { res.status(503).json({ ok: false, database: "unavailable" }); }
});

app.post("/api/auth/register", rateLimit({ max: 8 }), async (req, res) => {
  const displayName = String(req.body?.displayName || "").trim();
  const email = String(req.body?.email || "").trim().toLowerCase();
  const password = String(req.body?.password || "");
  if (displayName.length < 2 || displayName.length > 80 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 255 || password.length < 8 || password.length > 72)
    return res.status(400).json({ error: "INVALID_REGISTRATION", message: "Tên 2–80 ký tự, email hợp lệ và mật khẩu 8–72 ký tự là bắt buộc." });
  try {
    const hash = await bcrypt.hash(password, 12);
    const { rows } = await pool.query(
      "INSERT INTO users(display_name,email,password_hash,role) VALUES($1,$2,$3,'user') RETURNING id,display_name,email,role,avatar_url",
      [displayName, email, hash]);
    res.status(201).json({ user: rows[0], token: signToken(rows[0]) });
  } catch (e) {
    if (e.code === "23505") return res.status(409).json({ error: "EMAIL_IN_USE", message: "Email đã được sử dụng." });
    res.status(500).json({ error: "REGISTER_FAILED" });
  }
});

app.post("/api/auth/login", rateLimit({ max: 12 }), async (req, res) => {
  const identifier = String(req.body?.username || req.body?.email || "").trim();
  const password = String(req.body?.password || "");
  if (!identifier || !password) return res.status(400).json({ error: "MISSING_CREDENTIALS" });
  try {
    const { rows } = await pool.query("SELECT id,display_name,email,password_hash,role,avatar_url FROM users WHERE LOWER(email)=LOWER($1) OR LOWER(username)=LOWER($1) LIMIT 1", [identifier]);
    const user = rows[0];
    if (!user || !(await bcrypt.compare(password, user.password_hash))) return res.status(401).json({ error: "INVALID_CREDENTIALS", message: "Email hoặc mật khẩu không đúng." });
    delete user.password_hash;
    res.json({ user, token: signToken(user) });
  } catch { res.status(500).json({ error: "LOGIN_FAILED" }); }
});

app.get("/api/me", auth, async (req, res) => {
  try {
    const { rows } = await pool.query("SELECT id,display_name,email,avatar_url,role FROM users WHERE id=$1", [req.user.sub]);
    if (!rows[0]) return res.status(404).json({ error: "USER_NOT_FOUND" });
    res.json({ user: rows[0] });
  } catch { res.status(500).json({ error: "PROFILE_FAILED" }); }
});

// User discovery: only expose public profile fields, never email or credentials.
app.get("/api/users", auth, async (req, res) => {
  const q = String(req.query.q || "").trim().slice(0, 80);
  if (q.length < 2) return res.json({ users: [] });
  try {
    const { rows } = await pool.query(
      `SELECT id,display_name,avatar_url FROM users
       WHERE id<>$1 AND (display_name ILIKE $2 OR id::text=$3)
       ORDER BY display_name LIMIT 30`, [req.user.sub, `%${q.replace(/[\\%_]/g, "\\$&")}%`, q]);
    res.json({ users: rows });
  } catch { res.status(500).json({ error: "USER_SEARCH_FAILED" }); }
});

// Friend requests are directional until accepted; chat is enabled only after acceptance.
app.get("/api/friends", auth, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT u.id,u.display_name,u.avatar_url,f.status,(f.user_id=$1) AS outgoing
       FROM friendships f JOIN users u ON u.id=CASE WHEN f.user_id=$1 THEN f.friend_id ELSE f.user_id END
       WHERE f.user_id=$1 OR f.friend_id=$1 ORDER BY f.created_at DESC`, [req.user.sub]);
    res.json({ friends: rows.map(friend => ({ ...friend, online: onlineSockets.has(String(friend.id)) })) });
  } catch { res.status(500).json({ error: "FRIENDS_FAILED" }); }
});
app.post("/api/friends/request", auth, async (req, res) => {
  const friendId = req.body?.friendId;
  if (!validId(friendId) || String(friendId) === String(req.user.sub)) return res.status(400).json({ error: "INVALID_FRIEND_ID" });
  try {
    const existing = await pool.query(
      "SELECT user_id,friend_id,status FROM friendships WHERE (user_id=$1 AND friend_id=$2) OR (user_id=$2 AND friend_id=$1) ORDER BY status='blocked' DESC,status='accepted' DESC LIMIT 1",
      [req.user.sub, friendId]);
    const relation = existing.rows[0];
    if (relation?.status === "accepted") return res.json({ status: "accepted" });
    if (relation?.status === "blocked") return res.status(403).json({ error: "USER_BLOCKED" });
    if (relation?.status === "pending" && String(relation.user_id) === String(friendId)) {
      await pool.query("UPDATE friendships SET status='accepted' WHERE user_id=$1 AND friend_id=$2", [friendId, req.user.sub]);
      io.to(`user:${friendId}`).emit("friendship:accepted", { userId: String(req.user.sub) });
      io.to(`user:${req.user.sub}`).emit("friendship:accepted", { userId: String(friendId) });
      return res.json({ status: "accepted" });
    }
    if (relation?.status === "pending") return res.json({ status: "pending" });
    const { rows } = await pool.query(
      "INSERT INTO friendships(user_id,friend_id,status) VALUES($1,$2,'pending') RETURNING status",
      [req.user.sub, friendId]);
    res.status(201).json({ status: rows[0].status });
  } catch (e) { res.status(e.code === "23503" ? 404 : 500).json({ error: e.code === "23503" ? "USER_NOT_FOUND" : "FRIEND_REQUEST_FAILED" }); }
});
app.post("/api/friends/accept", auth, async (req, res) => {
  const requesterId = req.body?.requesterId;
  if (!validId(requesterId)) return res.status(400).json({ error: "INVALID_REQUESTER_ID" });
  try {
    const result = await pool.query("UPDATE friendships SET status='accepted' WHERE user_id=$1 AND friend_id=$2 AND status='pending'", [requesterId, req.user.sub]);
    if (!result.rowCount) return res.status(404).json({ error: "REQUEST_NOT_FOUND" });
    io.to(`user:${requesterId}`).emit("friendship:accepted", { userId: String(req.user.sub) });
    io.to(`user:${req.user.sub}`).emit("friendship:accepted", { userId: String(requesterId) });
    res.json({ status: "accepted" });
  } catch { res.status(500).json({ error: "FRIEND_ACCEPT_FAILED" }); }
});

app.get("/api/messages/:userId", auth, async (req, res) => {
  const peer = req.params.userId;
  if (!validId(peer) || String(peer) === String(req.user.sub)) return res.status(400).json({ error: "INVALID_USER_ID" });
  const limit = Math.min(100, Math.max(1, Number(req.query.limit) || 50));
  const before = req.query.before ? Number(req.query.before) : null;
  if (before !== null && (!Number.isSafeInteger(before) || before < 1)) return res.status(400).json({ error: "INVALID_CURSOR" });
  try {
    if (!(await canChat(req.user.sub, peer))) return res.status(403).json({ error: "FRIENDSHIP_REQUIRED" });
    const { rows } = await pool.query(
      `SELECT id,sender_id,receiver_id,body,created_at,read_at FROM messages
       WHERE ((sender_id=$1 AND receiver_id=$2) OR (sender_id=$2 AND receiver_id=$1))
       AND ($3::bigint IS NULL OR id<$3) ORDER BY id DESC LIMIT $4`, [req.user.sub, peer, before, limit]);
    res.json({ messages: rows.reverse(), nextBefore: rows.length === limit ? rows[rows.length - 1].id : null });
  } catch { res.status(500).json({ error: "MESSAGE_HISTORY_FAILED" }); }
});
app.post("/api/messages/:userId/read", auth, async (req, res) => {
  const peer = req.params.userId;
  if (!validId(peer) || !(await canChat(req.user.sub, peer))) return res.status(403).json({ error: "FRIENDSHIP_REQUIRED" });
  try {
    const result = await pool.query("UPDATE messages SET read_at=NOW() WHERE sender_id=$1 AND receiver_id=$2 AND read_at IS NULL", [peer, req.user.sub]);
    io.to(`user:${peer}`).emit("message:read", { by: String(req.user.sub) });
    res.json({ updated: result.rowCount });
  } catch { res.status(500).json({ error: "MARK_READ_FAILED" }); }
});
app.post("/api/messages/:userId", auth, async (req, res) => {
  const peer = req.params.userId;
  const body = String(req.body?.body || "").trim();
  const clientId = req.body?.clientId || null;
  if (!validId(peer) || String(peer) === String(req.user.sub) || !body || body.length > 5000 || (clientId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(clientId))) return res.status(400).json({ error: "INVALID_MESSAGE" });
  try {
    if (!(await canChat(req.user.sub, peer))) return res.status(403).json({ error: "FRIENDSHIP_REQUIRED" });
    const { rows } = await pool.query(
      `INSERT INTO messages(sender_id,receiver_id,body,client_id) VALUES($1,$2,$3,$4)
       ON CONFLICT(sender_id,client_id) WHERE client_id IS NOT NULL DO UPDATE SET client_id=EXCLUDED.client_id
       RETURNING id,sender_id,receiver_id,body,created_at,read_at`, [req.user.sub, peer, body, clientId]);
    io.to(`user:${peer}`).emit("message:new", rows[0]);
    io.to(`user:${req.user.sub}`).emit("message:sent", rows[0]);
    res.status(201).json({ message: rows[0] });
  } catch { res.status(500).json({ error: "MESSAGE_SEND_FAILED" }); }
});

app.post("/api/messages/:messageId/recall", auth, async (req, res) => {
  const messageId = req.params.messageId;
  if (!validId(messageId)) return res.status(400).json({ error: "INVALID_MESSAGE_ID" });
  try {
    const setting = await pool.query("SELECT setting_value FROM app_settings WHERE setting_key='anti_recall'");
    if (setting.rows[0]?.setting_value?.enabled === true) return res.status(403).json({ error: "RECALL_DISABLED_BY_ADMIN" });
    const { rows } = await pool.query(
      "UPDATE messages SET body='Tin nhắn đã thu hồi',recalled_at=NOW() WHERE id=$1 AND sender_id=$2 AND recalled_at IS NULL RETURNING id,sender_id,receiver_id,body,created_at,read_at,recalled_at",
      [messageId, req.user.sub]);
    if (!rows[0]) return res.status(404).json({ error: "MESSAGE_NOT_FOUND_OR_NOT_OWNER" });
    io.to(`user:${rows[0].sender_id}`).emit("message:recalled", rows[0]);
    io.to(`user:${rows[0].receiver_id}`).emit("message:recalled", rows[0]);
    res.json({ message: rows[0] });
  } catch { res.status(500).json({ error: "MESSAGE_RECALL_FAILED" }); }
});

// Stories are visible to the owner and accepted friends and expire automatically after 24h.
app.get("/api/stories", auth, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT s.id,s.user_id,s.media_url,s.caption,s.created_at,s.expires_at,u.display_name,u.avatar_url
       FROM stories s JOIN users u ON u.id=s.user_id
       WHERE s.expires_at>NOW() AND (s.user_id=$1 OR s.user_id IN (
         SELECT CASE WHEN user_id=$1 THEN friend_id ELSE user_id END FROM friendships
         WHERE status='accepted' AND (user_id=$1 OR friend_id=$1)))
       ORDER BY s.created_at DESC LIMIT 100`, [req.user.sub]);
    res.json({ stories: rows });
  } catch { res.status(500).json({ error: "STORIES_FAILED" }); }
});
app.post("/api/stories/upload", auth, rateLimit({ max: 10 }), upload.single("media"), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: "MEDIA_REQUIRED" });
  const mediaUrl = publicMediaUrl(req, req.file.filename);
  let story;
  try {
    const { rows } = await pool.query(
      "INSERT INTO stories(user_id,media_url,caption,expires_at) VALUES($1,$2,$3,NOW()+INTERVAL '24 hours') RETURNING *",
      [req.user.sub, mediaUrl, String(req.body?.caption || "").slice(0, 500) || null]);
    story = rows[0];
  } catch { fs.unlink(req.file.path, () => {}); return res.status(500).json({ error: "STORY_CREATE_FAILED" }); }
  io.to(`user:${req.user.sub}`).emit("story:new", story);
  pool.query(
    "SELECT CASE WHEN user_id=$1 THEN friend_id ELSE user_id END AS friend_id FROM friendships WHERE status='accepted' AND (user_id=$1 OR friend_id=$1)",
    [req.user.sub]).then(({ rows }) => rows.forEach(friend => io.to(`user:${friend.friend_id}`).emit("story:new", story))).catch(() => {});
  res.status(201).json({ story });
});

app.post("/api/stories", auth, async (req, res) => {
  const mediaUrl = req.body?.mediaUrl;
  const caption = String(req.body?.caption || "").slice(0, 500);
  if (typeof mediaUrl !== "string" || !/^https:\/\//i.test(mediaUrl) || mediaUrl.length > 2048)
    return res.status(400).json({ error: "HTTPS_MEDIA_URL_REQUIRED" });
  try {
    const { rows } = await pool.query(
      "INSERT INTO stories(user_id,media_url,caption,expires_at) VALUES($1,$2,$3,NOW()+INTERVAL '24 hours') RETURNING *",
      [req.user.sub, mediaUrl, caption || null]);
    const { rows: friends } = await pool.query(
      "SELECT CASE WHEN user_id=$1 THEN friend_id ELSE user_id END AS friend_id FROM friendships WHERE status='accepted' AND (user_id=$1 OR friend_id=$1)",
      [req.user.sub]);
    io.to(`user:${req.user.sub}`).emit("story:new", rows[0]);
    friends.forEach(friend => io.to(`user:${friend.friend_id}`).emit("story:new", rows[0]));
    res.status(201).json({ story: rows[0] });
  } catch { res.status(500).json({ error: "STORY_CREATE_FAILED" }); }
});

app.get("/api/stickers", auth, async (req, res) => {
  try { const { rows } = await pool.query("SELECT id,media_url,is_animated,created_at FROM stickers WHERE user_id=$1 ORDER BY id DESC LIMIT 100", [req.user.sub]); res.json({ stickers: rows }); }
  catch { res.status(500).json({ error: "STICKERS_FAILED" }); }
});
app.post("/api/stickers", auth, rateLimit({ max: 8 }), upload.single("media"), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: "MEDIA_REQUIRED" });
  const isVideo = req.file.mimetype.startsWith("video/");
  if (activeStickerJobs >= 2) { fs.unlink(req.file.path, () => {}); return res.status(503).json({ error: "STICKER_QUEUE_BUSY" }); }
  activeStickerJobs += 1;
  const outputName = `${crypto.randomUUID()}.webp`;
  const outputPath = path.join(UPLOAD_DIR, outputName);
  const stillFilter = "scale=512:512:force_original_aspect_ratio=decrease:flags=lanczos,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000";
  const animatedFilter = `fps=12,${stillFilter}`;
  const args = isVideo
    ? ["-y", "-i", req.file.path, "-t", "5", "-vf", animatedFilter, "-loop", "0", "-an", "-c:v", "libwebp_anim", "-q:v", "65", "-preset", "picture", outputPath]
    : ["-y", "-i", req.file.path, "-frames:v", "1", "-vf", stillFilter, "-an", "-c:v", "libwebp", "-q:v", "75", outputPath];
  try {
    await runFfmpeg(args);
    fs.unlink(req.file.path, () => {});
    const mediaUrl = publicMediaUrl(req, outputName);
    const { rows } = await pool.query("INSERT INTO stickers(user_id,media_url,is_animated) VALUES($1,$2,$3) RETURNING id,media_url,is_animated,created_at", [req.user.sub, mediaUrl, isVideo]);
    res.status(201).json({ sticker: rows[0] });
  } catch (e) { fs.unlink(req.file.path, () => {}); fs.unlink(outputPath, () => {}); res.status(422).json({ error: e.message === "MEDIA_PROCESS_TIMEOUT" ? e.message : "STICKER_CONVERSION_FAILED" }); }
  finally { activeStickerJobs -= 1; }
});

app.get("/api/admin/commands", auth, requireAdmin, (_req, res) => res.json({ commands: ["/antirecall on", "/antirecall off"] }));
app.post("/api/admin/settings/anti-recall", auth, requireAdmin, async (req, res) => {
  const enabled = req.body?.enabled;
  if (typeof enabled !== "boolean") return res.status(400).json({ error: "enabled must be boolean" });
  try {
    await pool.query(`INSERT INTO app_settings(setting_key,setting_value,updated_by) VALUES('anti_recall',$1::jsonb,$2)
      ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value,updated_by=EXCLUDED.updated_by,updated_at=NOW()`, [JSON.stringify({ enabled }), req.user.sub]);
    res.json({ antiRecallEnabled: enabled });
  } catch { res.status(500).json({ error: "SETTING_UPDATE_FAILED" }); }
});

app.get("/api/moments", auth, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT m.id,m.media_url,m.caption,m.created_at,u.id AS user_id,u.display_name,u.avatar_url
       FROM moments m JOIN users u ON u.id=m.user_id
       WHERE m.user_id=$1 OR m.user_id IN (SELECT CASE WHEN user_id=$1 THEN friend_id ELSE user_id END FROM friendships WHERE status='accepted' AND (user_id=$1 OR friend_id=$1))
       ORDER BY m.created_at DESC LIMIT 100`, [req.user.sub]);
    res.json({ moments: rows });
  } catch { res.status(500).json({ error: "MOMENTS_FAILED" }); }
});
app.post("/api/moments", auth, async (req, res) => {
  const { mediaUrl, caption } = req.body || {};
  if (!mediaUrl || !/^https:\/\//i.test(mediaUrl)) return res.status(400).json({ error: "HTTPS_MEDIA_URL_REQUIRED" });
  try {
    const { rows } = await pool.query("INSERT INTO moments(user_id,media_url,caption) VALUES($1,$2,$3) RETURNING *", [req.user.sub, mediaUrl, String(caption || "").slice(0, 500) || null]);
    io.emit("moment:new", rows[0]);
    res.status(201).json({ moment: rows[0] });
  } catch { res.status(500).json({ error: "MOMENT_CREATE_FAILED" }); }
});

io.use((socket, next) => {
  try {
    const token = socket.handshake.auth?.token;
    socket.user = jwt.verify(token, process.env.JWT_SECRET, { issuer: "dkconnect", audience: "dkconnect-app" });
    next();
  } catch { next(new Error("UNAUTHORIZED")); }
});
const onlineSockets = new Map();
io.on("connection", socket => {
  const uid = String(socket.user.sub);
  socket.join(`user:${uid}`);
  onlineSockets.set(uid, (onlineSockets.get(uid) || 0) + 1);
  pool.query(`SELECT CASE WHEN user_id=$1 THEN friend_id ELSE user_id END AS friend_id
    FROM friendships WHERE status='accepted' AND (user_id=$1 OR friend_id=$1)`, [uid])
    .then(({ rows }) => rows.forEach(row => io.to(`user:${row.friend_id}`).emit("presence:update", { userId: uid, online: true })))
    .catch(() => {});

  const typingTargets = new Set();
  socket.on("typing:start", async (payload = {}) => {
    const target = String(payload.to || "");
    if (!validId(target) || !(await canChat(uid, target))) return;
    typingTargets.add(target);
    io.to(`user:${target}`).emit("typing:update", { userId: uid, typing: true });
  });
  socket.on("typing:stop", async (payload = {}) => {
    const target = String(payload.to || "");
    if (!validId(target) || !(await canChat(uid, target))) return;
    typingTargets.delete(target);
    io.to(`user:${target}`).emit("typing:update", { userId: uid, typing: false });
  });
  socket.on("message:send", async (payload = {}, ack = () => {}) => {
    try {
      const receiverId = String(payload.receiverId || "");
      const body = String(payload.body || "").trim();
      const clientId = payload.clientId || null;
      if (!validId(receiverId) || receiverId === uid || !body || body.length > 5000 || (clientId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(clientId))) return ack({ ok: false, error: "INVALID_MESSAGE" });
      if (!(await canChat(uid, receiverId))) return ack({ ok: false, error: "FRIENDSHIP_REQUIRED" });
      const { rows } = await pool.query(
        `INSERT INTO messages(sender_id,receiver_id,body,client_id) VALUES($1,$2,$3,$4)
         ON CONFLICT(sender_id,client_id) WHERE client_id IS NOT NULL DO UPDATE SET client_id=EXCLUDED.client_id
         RETURNING id,sender_id,receiver_id,body,created_at,read_at`, [uid, receiverId, body, clientId]);
      io.to(`user:${receiverId}`).emit("message:new", rows[0]);
      io.to(`user:${uid}`).emit("message:sent", rows[0]);
      ack({ ok: true, message: rows[0] });
    } catch { ack({ ok: false, error: "MESSAGE_SEND_FAILED" }); }
  });
  // Call signaling is relayed only between accepted friends; the mobile WebRTC client is a separate pending task.
  for (const event of ["call:offer", "call:answer", "call:ice", "call:hangup"]) {
    socket.on(event, async (payload = {}) => {
      const target = String(payload.to || "");
      if (!validId(target) || !(await canChat(uid, target))) return;
      io.to(`user:${target}`).emit(event, { ...payload, to: undefined, from: uid });
    });
  }
  socket.on("disconnect", () => {
    typingTargets.forEach(target => io.to(`user:${target}`).emit("typing:update", { userId: uid, typing: false }));
    typingTargets.clear();
    const remaining = (onlineSockets.get(uid) || 1) - 1;
    if (remaining <= 0) {
      onlineSockets.delete(uid);
      pool.query(`SELECT CASE WHEN user_id=$1 THEN friend_id ELSE user_id END AS friend_id
        FROM friendships WHERE status='accepted' AND (user_id=$1 OR friend_id=$1)`, [uid])
        .then(({ rows }) => rows.forEach(row => io.to(`user:${row.friend_id}`).emit("presence:update", { userId: uid, online: false })))
        .catch(() => {});
    } else onlineSockets.set(uid, remaining);
  });
});

app.use((err, _req, res, _next) => {
  if (err instanceof multer.MulterError) return res.status(err.code === "LIMIT_FILE_SIZE" ? 413 : 400).json({ error: err.code });
  if (err?.message === "UNSUPPORTED_MEDIA_TYPE") return res.status(415).json({ error: "UNSUPPORTED_MEDIA_TYPE" });
  res.status(500).json({ error: "INTERNAL_SERVER_ERROR" });
});

if (require.main === module) {
  (async () => {
    await ensureBootstrapAdmin();
    server.listen(PORT, () => console.log(`DK Connect API listening on ${PORT}`));
  })().catch(error => { console.error("DK Connect startup failed:", error.message); process.exit(1); });
}
module.exports = { app, server, pool, io };
