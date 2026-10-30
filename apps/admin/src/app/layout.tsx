import type { Metadata } from "next";
import "./globals.css";
export const metadata:Metadata={title:"Camp Room · 운영 관리",description:"캠프룸 캠핑장 수령 조건과 준비물 주문을 관리합니다.",robots:{index:false,follow:false}};
export default function Layout({children}:Readonly<{children:React.ReactNode}>){return <html lang="ko"><body>{children}</body></html>;}
