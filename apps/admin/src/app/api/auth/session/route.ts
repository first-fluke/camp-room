import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { authenticated, authConfigured } from "@/lib/admin-server";
export async function GET(request:NextRequest){return NextResponse.json({authenticated:authenticated(request),configured:authConfigured()},{headers:{"Cache-Control":"no-store"}});}
