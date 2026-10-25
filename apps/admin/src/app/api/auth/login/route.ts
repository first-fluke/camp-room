import { NextResponse, type NextRequest } from "next/server";
import { authConfigured, COOKIE_NAME, fail, mutationAllowed } from "@/lib/admin-server";
import { passwordMatches, SESSION_SECONDS, signSession } from "@/lib/admin-session.mjs";
export const runtime="nodejs";
let failures=0;let windowStart=Date.now();
export async function POST(request:NextRequest) {
 if(!mutationAllowed(request))return fail("허용되지 않은 요청 출처입니다.",403);
 if(!authConfigured())return fail("관리자 로그인 설정이 아직 준비되지 않았습니다.",503);
 if(Date.now()-windowStart>300_000){failures=0;windowStart=Date.now();}
 if(failures>=10)return fail("로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.",429);
 const body=await request.json().catch(()=>null);
 if(!passwordMatches(body?.password,process.env.CAMP_ADMIN_PASSWORD)){failures++;return fail("비밀번호를 확인해 주세요.",401);}
 failures=0;
 const response=NextResponse.json({authenticated:true},{headers:{"Cache-Control":"no-store"}});
 response.cookies.set(COOKIE_NAME,signSession(process.env.CAMP_ADMIN_SESSION_SECRET!),{httpOnly:true,sameSite:"strict",secure:request.nextUrl.protocol==="https:",path:"/",maxAge:SESSION_SECONDS});
 return response;
}
