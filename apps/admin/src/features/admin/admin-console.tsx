"use client";
import { AdminLocaleProvider, useAdminLocale, readAdminLocale, adminText as t, translateAdminSource, localizedAdminError } from "@/features/admin/admin-locale";
import { useEffect, useState } from "react";
import { Tent, SquaresFour, MapPin, Package, SignOut, ArrowClockwise, CheckCircle, WarningCircle, LockKey, ArrowRight, Database, PlugsConnected, Check, CaretRight, CircleNotch, X, Storefront, Truck } from "@phosphor-icons/react";
import type { AdminCampsite, AdminOrder, AdminOverview } from "@/features/admin/admin.types";
const won = (value: number) => new Intl.NumberFormat(readAdminLocale() === "ko" ? "ko-KR" : "en-US", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0
}).format(value);
const when = (value: string) => value ? new Date(value).toLocaleString(readAdminLocale() === "ko" ? "ko-KR" : "en-US", {
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit"
}) : "—";
function Brand() {
  return <div className="brand"><Tent size={29} weight="duotone" /><span>camp room<small>OPERATIONS CONSOLE</small></span></div>;
}
function Loading() {
  return <CircleNotch size={18} className="spin" />;
}
class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}
async function call<T>(url: string, method = "GET", body?: unknown): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: {
      "Content-Type": "application/json"
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store"
  });
  const data = await response.json().catch(() => ({
    detail: t("admin_006")
  }));
  if (!response.ok) throw new ApiError(response.status, localizedAdminError(response.status, typeof data.detail === "string" ? data.detail : "", url));
  return data as T;
}
export function AdminConsole() {
  return <AdminLocaleProvider><AdminConsoleContent /></AdminLocaleProvider>;
}
function AdminConsoleContent() {
  const {
    locale,
    setLocale,
    t
  } = useAdminLocale();
  const STATUS: Record<string, string> = {
    pending: t("admin_001"),
    packing: t("admin_002"),
    ready: t("admin_003"),
    cancelled: t("admin_004")
  };
  const [loading, setLoading] = useState(true);
  const [authenticated, setAuthenticated] = useState(false);
  const [configured, setConfigured] = useState(true);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [overview, setOverview] = useState<AdminOverview | null>(null);
  const [tab, setTab] = useState<"overview" | "campsites" | "orders">("overview");
  const [siteId, setSiteId] = useState("");
  const [accepts, setAccepts] = useState(false);
  const [location, setLocation] = useState("");
  const [instructions, setInstructions] = useState("");
  const [orderId, setOrderId] = useState("");
  const [fulfillment, setFulfillment] = useState("pending");
  const [adminNote, setAdminNote] = useState("");
  function chooseSite(site: AdminCampsite) {
    setSiteId(site.id);
    setAccepts(site.delivery.acceptsParcels);
    setLocation(site.delivery.location ?? "");
    setInstructions(Array.isArray(site.delivery.instructions) ? site.delivery.instructions.join("\n") : site.delivery.instructions ?? "");
  }
  function chooseOrder(order: AdminOrder) {
    setOrderId(order.id);
    setFulfillment(order.fulfillment?.status ?? "pending");
    setAdminNote(order.fulfillment?.adminNote ?? "");
  }
  async function loadOverview() {
    const next = await call<AdminOverview>("/api/admin/overview");
    setOverview(next);
    const site = next.campsites.find(value => value.id === siteId) ?? next.campsites[0];
    if (site) chooseSite(site);
    const order = next.orders.find(value => value.id === orderId) ?? next.orders[0];
    if (order) chooseOrder(order);else setOrderId("");
  }
  function handleError(value: unknown) {
    if (value instanceof ApiError && value.status === 401) {
      setAuthenticated(false);
      setOverview(null);
    }
    setError(value instanceof ApiError ? translateAdminSource(value.message) : t("api_unreachable"));
  }
  async function run(task: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await task();
    } catch (value) {
      handleError(value);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    let alive = true;
    void call<{
      authenticated: boolean;
      configured: boolean;
    }>("/api/auth/session").then(async session => {
      if (!alive) return;
      setConfigured(session.configured);
      setAuthenticated(session.authenticated);
      if (session.authenticated) {
        const next = await call<AdminOverview>("/api/admin/overview");
        if (!alive) return;
        setOverview(next);
        if (next.campsites[0]) chooseSite(next.campsites[0]);
        if (next.orders[0]) chooseOrder(next.orders[0]);
      }
    }).catch(value => {
      if (alive) handleError(value);
    }).finally(() => {
      if (alive) setLoading(false);
    });
    return () => {
      alive = false;
    };
  }, []);
  const selectedSite = overview?.campsites.find(site => site.id === siteId);
  const selectedOrder = overview?.orders.find(order => order.id === orderId);
  async function login(event: React.FormEvent) {
    event.preventDefault();
    await run(async () => {
      await call("/api/auth/login", "POST", {
        password
      });
      setPassword("");
      setAuthenticated(true);
      await loadOverview();
    });
  }
  async function logout() {
    await run(async () => {
      await call("/api/auth/logout", "POST");
      setAuthenticated(false);
      setOverview(null);
      setPassword("");
    });
  }
  async function saveReceiving(event: React.FormEvent) {
    event.preventDefault();
    await run(async () => {
      await call(`/api/admin/campsites/${encodeURIComponent(siteId)}/receiving`, "PATCH", {
        acceptsParcels: accepts,
        location,
        instructions
      });
      await loadOverview();
      setNotice(t("admin_008"));
    });
  }
  async function saveFulfillment(event: React.FormEvent) {
    event.preventDefault();
    await run(async () => {
      await call(`/api/admin/orders/${encodeURIComponent(orderId)}`, "PATCH", {
        fulfillmentStatus: fulfillment,
        adminNote
      });
      await loadOverview();
      setNotice(t("admin_009"));
    });
  }
  if (loading) return <main className="login-page"><Brand /><div className="login-loading"><Loading /><p>{t("admin_010")}</p></div></main>;
  if (!authenticated) return <main className="login-page"><div className="login-landscape"><div className="mountain one" /><div className="mountain two" /><Tent weight="duotone" /><div><span>MAKE ROOM FOR BETTER CAMPING</span><h1>{t("admin_011")}<br />{t("admin_012")}</h1><p>{t("admin_013")}<br />{t("admin_014")}</p></div></div><section className="login-card"><LocaleControls onChange={() => {setError("");setNotice("");}}/><Brand /><div className="login-heading"><span className="eyebrow"><LockKey size={15} /> PRIVATE OPERATIONS</span><h2>{t("admin_015")}</h2><p>{t("admin_016")}</p></div>{error && <div className="alert error" role="alert"><WarningCircle size={19} />{error}</div>}{!configured && <div className="alert error"><WarningCircle size={19} />{t("admin_017")}</div>}<form onSubmit={login}><label htmlFor="password">{t("admin_018")}</label><input id="password" data-testid="admin-password" type="password" autoComplete="current-password" placeholder={t("admin_019")} value={password} onChange={event => setPassword(event.target.value)} required /><button data-testid="admin-login" className="primary-button" disabled={busy || !configured}>{busy ? <Loading /> : <ArrowRight size={19} />}{t("admin_020")}</button></form><p className="login-note"><LockKey size={13} />{t("admin_021")}</p></section></main>;
  return <div className="admin-shell"><aside className="admin-sidebar"><Brand /><span className="sidebar-label">WORKSPACE</span><nav aria-label={t("admin_022")}>{([{
          id: "overview",
          title: t("admin_023"),
          description: t("admin_024"),
          icon: SquaresFour
        }, {
          id: "campsites",
          title: t("admin_025"),
          description: t("admin_026"),
          icon: MapPin
        }, {
          id: "orders",
          title: t("admin_027"),
          description: t("admin_028"),
          icon: Package
        }] as const).map(item => <button key={item.id} data-testid={`admin-nav-${item.id}`} className={tab === item.id ? "active" : ""} onClick={() => setTab(item.id)}><item.icon size={24} weight={tab === item.id ? "fill" : "regular"} /><span>{item.title}<small>{item.description}</small></span><CaretRight size={14} /></button>)}</nav><div className="sidebar-foot"><Tent size={39} weight="duotone" /><p>{t("admin_029")}<br />{t("admin_030")}</p><span>{t("admin_031")}</span><button data-testid="admin-logout" onClick={logout} disabled={busy}><SignOut size={18} />{t("admin_032")}</button></div></aside><div className="admin-main"><header className="admin-topbar"><span>Camp Room <i>/</i>{t("admin_033")}</span><div><LocaleControls onChange={() => {setError("");setNotice("");}}/><span className="private-badge"><LockKey size={12} />{t("admin_034")}</span><button onClick={() => run(loadOverview)} disabled={busy} data-testid="admin-refresh">{busy ? <Loading /> : <ArrowClockwise size={18} />}{t("admin_035")}</button><span className="avatar">A</span></div></header><main className="admin-content"><div className="page-heading"><div><span className="eyebrow">CAMP ROOM OPERATIONS</span><h1>{tab === "overview" ? t("admin_036") : tab === "campsites" ? t("admin_037") : t("admin_038")}</h1><p>{tab === "overview" ? t("admin_039") : tab === "campsites" ? t("admin_040") : t("admin_041")}</p></div><span className="page-mark"><Storefront size={40} weight="duotone" /></span></div>
 {error && <div className="alert error" role="alert"><WarningCircle size={20} /><span>{error}</span><button aria-label={t("admin_042")} onClick={() => setError("")}><X size={17} /></button></div>}{notice && <div className="alert success" role="status"><CheckCircle size={20} /><span>{notice}</span><button aria-label={t("admin_043")} onClick={() => setNotice("")}><X size={17} /></button></div>}
 {!overview ? <section className="empty-state"><PlugsConnected size={45} /><h2>{t("admin_044")}</h2><p>{t("admin_045")}</p><button className="secondary-button" onClick={() => run(loadOverview)}>{t("admin_046")}</button></section> : <>
 {tab === "overview" && <><div className="stats-grid"><Stat label={t("admin_047")} value={String(overview.counts.campsites)} unit={t("admin_048")} icon={<MapPin size={24} />} /><Stat label={t("admin_027")} value={String(overview.counts.orders)} unit={t("admin_049")} icon={<Package size={24} />} /><Stat label={t("admin_050")} value={String(overview.counts.openOrders)} unit={t("admin_049")} icon={<Truck size={24} />} /><Stat label={t("admin_051")} value={overview.database.backend.toUpperCase()} unit="" icon={<Database size={24} />} /></div><section className="surface"><div className="section-heading"><div><h2>{t("admin_052")}</h2><p>{t("admin_053")}</p></div><button data-testid="admin-provider-refresh" className="secondary-button provider-refresh-button" disabled={busy} onClick={() => run(async () => { const result = await call<{status:string}>("/api/admin/providers/refresh","POST",{force:false}); await loadOverview(); setNotice(result.status === "refreshed" ? t("provider_refreshed") : result.status === "busy" ? t("provider_busy") : t("provider_cached")); })}>{busy ? <Loading/> : <ArrowClockwise size={16}/>} {t("provider_refresh")}</button></div><div className="provider-grid">{overview.providers.map(provider => <article className="provider-card" key={provider.name}><div><PlugsConnected size={23} /><span className={`status-badge ${provider.configured ? "configured" : "missing"}`}>{provider.configured ? t("admin_055") : t("admin_056")}</span></div><h3>{provider.name}</h3><p>{provider.message || t("admin_057")}</p><footer><span>{provider.mode === "live" ? t("admin_058") : provider.mode === "demo" ? t("admin_059") : provider.mode}</span><small>{provider.status}</small></footer></article>)}</div><div className="database-detail"><Database size={21} /><span><strong>{overview.database.backend === "turso" ? t("admin_060") : t("admin_061")}</strong><small>{t("admin_062")}{overview.database.namespace}</small></span><CheckCircle size={20} /></div></section><div className="overview-actions"><button onClick={() => setTab("campsites")}><MapPin size={27} /><span><strong>{t("admin_063")}</strong><small>{t("admin_064")}</small></span><ArrowRight size={20} /></button><button onClick={() => setTab("orders")}><Package size={27} /><span><strong>{t("admin_065")}</strong><small>{t("admin_066")}</small></span><ArrowRight size={20} /></button></div></>}
 {tab === "campsites" && <section className="management-grid"><div className="record-list"><div className="list-heading"><h2>{t("admin_067")}</h2><span>{overview.campsites.length}{t("admin_048")}</span></div>{overview.campsites.map(site => <button data-testid={`admin-site-${site.id}`} key={site.id} disabled={busy} className={siteId === site.id ? "selected" : ""} onClick={() => chooseSite(site)}><span className="record-icon"><Tent size={23} /></span><span><strong>{site.name}</strong><small>{site.region} · {site.delivery.acceptsParcels ? t("admin_068") : t("admin_069")}</small></span><CaretRight size={16} /></button>)}</div><section className="surface editor-panel">{selectedSite ? <><div className="section-heading"><div><span className="eyebrow">CAMPSITE RECEIVING</span><h2>{selectedSite.name}</h2><p>{selectedSite.address}</p></div><span className="small-pill">{selectedSite.source?.mode === "demo" ? t("admin_070") : t("admin_071")}</span></div><form onSubmit={saveReceiving}><label className="receiving-toggle"><span><strong>{t("admin_072")}</strong><small>{t("admin_073")}</small></span><input data-testid="admin-receiving-enabled" type="checkbox" checked={accepts} onChange={event => setAccepts(event.target.checked)} /><b>{accepts ? t("admin_074") : t("admin_075")}</b></label><label htmlFor="receiving-location">{t("admin_076")}</label><input id="receiving-location" data-testid="admin-receiving-location" value={location} maxLength={500} onChange={event => setLocation(event.target.value)} placeholder={t("admin_077")} required={accepts} /><label htmlFor="receiving-instructions">{t("admin_078")}</label><textarea id="receiving-instructions" data-testid="admin-receiving-instructions" value={instructions} maxLength={3000} onChange={event => setInstructions(event.target.value)} rows={5} placeholder={t("admin_079")} /><div className="editor-note"><CheckCircle size={18} /><span>{t("admin_080")}</span></div>{selectedSite.delivery.updatedAt && <p className="updated-note">{t("admin_081")}{when(selectedSite.delivery.updatedAt)}</p>}<button data-testid="admin-save-receiving" className="primary-button" disabled={busy}>{busy ? <Loading /> : <Check size={18} />}{t("admin_082")}</button></form></> : <div className="empty-state"><h2>{t("admin_083")}</h2></div>}</section></section>}
 {tab === "orders" && <section className="management-grid"><div className="record-list"><div className="list-heading"><h2>{t("admin_027")}</h2><span>{overview.orders.length}{t("admin_049")}</span></div>{overview.orders.length === 0 ? <div className="list-empty"><Package size={35} /><p>{t("admin_084")}</p></div> : overview.orders.map(order => <button data-testid={`admin-order-${order.id}`} key={order.id} disabled={busy} className={orderId === order.id ? "selected" : ""} onClick={() => chooseOrder(order)}><span className="record-icon"><Package size={23} /></span><span><strong>{order.id}</strong><small>{when(order.createdAt)} · {STATUS[order.fulfillment?.status ?? "pending"] ?? t("admin_001")}</small></span><CaretRight size={16} /></button>)}</div><section className="surface editor-panel">{selectedOrder ? <><div className="section-heading"><div><span className="eyebrow">ORDER FULFILLMENT</span><h2>{selectedOrder.id}</h2><p>{when(selectedOrder.createdAt)}{t("admin_085")}</p></div><span className="small-pill">{t("admin_086")}</span></div><div className="demo-notice"><WarningCircle size={19} /><p>{t("admin_087")}</p></div><div className="order-summary"><div><span>{t("admin_088")}</span><strong>{selectedOrder.recipientName || "—"} {selectedOrder.siteReference}</strong></div><div><span>{t("admin_089")}</span><strong>{selectedOrder.destination === "home" ? t("admin_090") : t("admin_067")} · {selectedOrder.address}</strong></div><div><span>{t("admin_091")}</span><strong>{selectedOrder.eta}</strong></div></div><div className="order-lines">{selectedOrder.items.map((item, index) => <div key={`${item.name}-${index}`}><span>{item.name} <small>× {item.quantity}</small></span><strong>{won(item.lineTotal)}</strong></div>)}<div className="order-total"><span>{t("admin_092")}</span><strong>{won(selectedOrder.total)}</strong></div></div><form onSubmit={saveFulfillment}><label htmlFor="fulfillment-status">{t("admin_093")}</label><select data-testid="admin-fulfillment-status" id="fulfillment-status" value={fulfillment} onChange={event => setFulfillment(event.target.value)}>{Object.entries(STATUS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select><label htmlFor="admin-note">{t("admin_094")}</label><textarea data-testid="admin-order-note" id="admin-note" value={adminNote} maxLength={3000} onChange={event => setAdminNote(event.target.value)} rows={4} placeholder={t("admin_095")} />{selectedOrder.fulfillment?.updatedAt && <p className="updated-note">{t("admin_081")}{when(selectedOrder.fulfillment.updatedAt)}</p>}<button data-testid="admin-save-order" className="primary-button" disabled={busy}>{busy ? <Loading /> : <Check size={18} />}{t("admin_096")}</button></form></> : <div className="empty-state"><Package size={43} /><h2>{t("admin_097")}</h2><p>{t("admin_098")}</p></div>}</section></section>}
 </>}
 <footer className="admin-footer"><Tent size={16} /><strong>camp room</strong><span>{t("admin_099")}</span><small>{t("admin_100")}</small></footer></main></div></div>;
}
function Stat({
  label,
  value,
  unit,
  icon
}: {
  label: string;
  value: string;
  unit: string;
  icon: React.ReactNode;
}) {
  const {
    t
  } = useAdminLocale();
  return <article className="stat-card"><div><span>{label}</span>{icon}</div><strong>{value}<small>{Number(value)===1 ? (unit===t("admin_048") ? t("unit_site_one") : unit===t("admin_049") ? t("unit_order_one") : unit) : unit}</small></strong><p>{label === t("admin_051") ? t("admin_101") : t("admin_102")}</p></article>;
}

function LocaleControls({onChange}:{onChange:()=>void}) {
    const {locale,setLocale,t}=useAdminLocale();
    return <div className="locale-controls" role="group" aria-label={t("language_settings")}>
        <button data-testid="admin-language-en" aria-pressed={locale==="en"} className={locale==="en"?"selected":""} onClick={()=>{setLocale("en");onChange();}}>{t("language_english")}</button>
        <button data-testid="admin-language-ko" aria-pressed={locale==="ko"} className={locale==="ko"?"selected":""} onClick={()=>{setLocale("ko");onChange();}}>{t("language_korean")}</button>
    </div>;
}
