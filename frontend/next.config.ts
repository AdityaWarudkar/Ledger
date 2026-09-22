import type { NextConfig } from "next";

const config: NextConfig = {
  output: "standalone",
  devIndicators: false,
  reactStrictMode: true,
  poweredByHeader: false,
};
export default config;
