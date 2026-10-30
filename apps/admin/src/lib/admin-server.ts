import "server-only";
import { loadEnvConfig } from "@next/env";
import path from "node:path";
import { NextResponse, type NextRequest } from "next/server";
import { isTrustedOrigin, verifySession, validResourceId } from "@/lib/admin-session.mjs";
loadEnvConfig(path.resolve(process.cwd(), "../.."));
export const COOKIE_NAME="camp_admin_session";
export function authConfigured() { return !!process.env.CAMP_ADMIN_PASSWORD && process.env.CAMP_ADMIN_PASSWORD.length>=12 && !!process.env.CAMP_ADMIN_SESSION_SECRET && process.env.CAMP_ADMIN_SESSION_SECRET.length>=32; }
export function authenticated(request:NextRequest) { return authConfigured() && verifySession(request.cookies.get(COOKIE_NAME)?.value,process.env.CAMP_ADMIN_SESSION_SECRET); }
export function mutationAllowed(request:NextRequest) { const host=request.headers.get("host"); const expectedOrigin=host?`${request.nextUrl.protocol}//${host}`:request.nextUrl.origin; return isTrustedOrigin(request.headers.get("origin"),expectedOrigin,request.headers.get("sec-fetch-site")); }
export function fail(detail:string,status:number) { return NextResponse.json({detail},{status,headers:{"Cache-Control":"no-store"}}); }
export async function proxyAdmin(request:NextRequest,route:string,method="GET",body?:unknown) {
 if(!authenticated(request))return fail("관리자 로그인이 필요합니다.",401);
 if(method!=="GET"&&!mutationAllowed(request))return fail("허용되지 않은 요청 출처입니다.",403);
 const token=process.env.CAMP_ADMIN_TOKEN;
 if(!token)return fail("관리 API 인증 설정이 아직 준비되지 않았습니다.",503);
 const base=(process.env.CAMP_API_BASE_URL||"http://127.0.0.1:8000").replace(/\/+$/,"").replace(/\/api\/v1$/,"");
 try {
  const response=await fetch(`${base}/api/v1/admin${route}`,{method,headers:{"Authorization":`Bearer ${token}`,"Content-Type":"application/json"},body:body===undefined?undefined:JSON.stringify(body),cache:"no-store",signal:AbortSignal.timeout(12_000)});
  const payload=await response.json().catch(()=>({detail:"관리 API 응답을 읽지 못했습니다."}));
  return NextResponse.json(payload,{status:response.status,headers:{"Cache-Control":"no-store"}});
 } catch {return fail("관리 API와 연결되지 않았습니다. 잠시 후 다시 시도해 주세요.",502);}
}
export function checkedId(id:string){return validResourceId(id);}
