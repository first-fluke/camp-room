import type { NextRequest } from "next/server";
import { proxyAdmin } from "@/lib/admin-server";
export async function GET(request:NextRequest){return proxyAdmin(request,"/overview");}
