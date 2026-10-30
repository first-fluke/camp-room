export const SESSION_SECONDS: number;
export function passwordMatches(input:unknown,expected:unknown):boolean;
export function signSession(secret:string,now?:number):string;
export function verifySession(token:unknown,secret:unknown,now?:number):boolean;
export function isTrustedOrigin(origin:string|null,expectedOrigin:string,fetchSite:string|null):boolean;
export function validResourceId(value:unknown):boolean;
