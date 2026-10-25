import { createHmac, randomBytes, timingSafeEqual, createHash } from "node:crypto";
export const SESSION_SECONDS = 8 * 60 * 60;
export function passwordMatches(input, expected) {
  if (typeof input !== "string" || typeof expected !== "string" || expected.length < 12 || input.length > 4096) return false;
  return timingSafeEqual(createHash("sha256").update(input).digest(), createHash("sha256").update(expected).digest());
}
export function signSession(secret, now = Math.floor(Date.now()/1000)) {
  if(typeof secret !== "string" || secret.length < 32) throw new Error("Session configuration missing");
  const payload=Buffer.from(JSON.stringify({v:1,iat:now,exp:now+SESSION_SECONDS,nonce:randomBytes(18).toString("base64url")})).toString("base64url");
  return payload+"."+createHmac("sha256",secret).update(payload).digest("base64url");
}
export function verifySession(token, secret, now = Math.floor(Date.now()/1000)) {
  if(typeof token!=="string" || token.length>2048 || typeof secret!=="string" || secret.length<32) return false;
  const parts=token.split("."); if(parts.length!==2 || !parts.every(part=>/^[A-Za-z0-9_-]+$/.test(part)))return false;
  const expected=createHmac("sha256",secret).update(parts[0]).digest(); let actual;
  try { actual=Buffer.from(parts[1],"base64url"); } catch { return false; }
  if(actual.length!==expected.length || !timingSafeEqual(actual,expected))return false;
  try { const value=JSON.parse(Buffer.from(parts[0],"base64url").toString("utf8")); return value.v===1 && Number.isInteger(value.iat) && Number.isInteger(value.exp) && value.iat<=now+30 && value.exp>now && value.exp-value.iat===SESSION_SECONDS && typeof value.nonce==="string" && value.nonce.length>=20; } catch { return false; }
}
export function isTrustedOrigin(origin, expectedOrigin, fetchSite) {
  if(fetchSite==="cross-site")return false;
  if(!origin)return true;
  try { return new URL(origin).origin===new URL(expectedOrigin).origin; } catch { return false; }
}
export function validResourceId(value) { return typeof value==="string" && /^[A-Za-z0-9_-]{1,100}$/.test(value); }
