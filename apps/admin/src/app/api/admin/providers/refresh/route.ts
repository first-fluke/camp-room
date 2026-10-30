import type { NextRequest } from "next/server";
import { fail, proxyAdmin } from "@/lib/admin-server";
export async function POST(request:NextRequest){const body=await request.json().catch(()=>null);if(body?.force!==undefined&&typeof body.force!=="boolean")return fail("유효하지 않은 갱신 요청입니다.",422);return proxyAdmin(request,"/providers/refresh","POST",{force:body?.force??false});}
