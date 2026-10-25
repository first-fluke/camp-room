import { loadEnvConfig } from "@next/env";
import path from "node:path";
import type { NextConfig } from "next";
loadEnvConfig(path.resolve(process.cwd(), "../.."));
const config: NextConfig = {
  poweredByHeader: false, devIndicators: false,
  async headers() { return [{ source: "/:path*", headers: [{key:"X-Frame-Options",value:"DENY"},{key:"X-Content-Type-Options",value:"nosniff"},{key:"Referrer-Policy",value:"same-origin"},{key:"Cache-Control",value:"no-store"}] }]; }
};
export default config;
