import { NextResponse, type NextRequest } from "next/server";
import { COOKIE_NAME, fail, mutationAllowed } from "@/lib/admin-server";
export async function POST(request:NextRequest){if(!mutationAllowed(request))return fail("허용되지 않은 요청 출처입니다.",403);const response=NextResponse.json({authenticated:false});response.cookies.set(COOKIE_NAME,"",{httpOnly:true,sameSite:"strict",secure:request.nextUrl.protocol==="https:",path:"/",maxAge:0});return response;}
