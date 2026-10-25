"use client";
import { createContext, useContext, useEffect, useState } from "react";
import { adminMessages, type AdminMessageKey } from "@/features/admin/admin.messages";
export type AdminLocale="ko"|"en";
export function readAdminLocale():AdminLocale{if(typeof window==="undefined")return "en";const saved=window.localStorage.getItem("camp_admin_locale");return saved==="ko"||saved==="en"?saved:navigator.language.toLowerCase().startsWith("ko")?"ko":"en";}
export function adminText(key:AdminMessageKey):string{return adminMessages[readAdminLocale()][key];}
export function translateAdminSource(value:string):string {const match=Object.entries(adminMessages.ko).find(([,text])=>text===value);return match?adminMessages[readAdminLocale()][match[0] as AdminMessageKey]:value;}
const LocaleContext=createContext<{locale:AdminLocale;setLocale:(value:AdminLocale)=>void;t:(key:AdminMessageKey)=>string}|null>(null);
export function AdminLocaleProvider({children}:{children:React.ReactNode}){const[locale,setLanguage]=useState<AdminLocale>("en");useEffect(()=>{setLanguage(readAdminLocale());},[]);useEffect(()=>{document.documentElement.lang=locale;document.title=`Camp Room · ${adminMessages[locale].admin_033}`;},[locale]);function setLocale(value:AdminLocale){window.localStorage.setItem("camp_admin_locale",value);setLanguage(value);}return <LocaleContext.Provider value={{locale,setLocale,t:(key)=>adminMessages[locale][key]}}>{children}</LocaleContext.Provider>;}
export function useAdminLocale(){const value=useContext(LocaleContext);if(!value)throw new Error("AdminLocaleProvider missing");return value;}

export function localizedAdminError(status:number,message:string,path:string):string {
    const known=Object.entries(adminMessages.ko).find(([,text])=>text===message);
    if(known)return adminMessages[readAdminLocale()][known[0] as AdminMessageKey];
    const key:AdminMessageKey=status===401?(path.includes("/auth/login")?"auth_wrong_password":"auth_required"):
        status===403?"error_forbidden":status===400||status===422?"error_validation":status===404?"error_missing":
        status===409?"error_conflict":status===429?"auth_rate_limited":status===503?"error_service_config":
        status>=500?"error_server":"admin_007";
    return adminMessages[readAdminLocale()][key];
}
