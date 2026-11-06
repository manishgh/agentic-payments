type Json = Record<string, unknown>;
type Checkout = {intentId: string; version: number; state: string; merchantId: string; checkoutRevision: number; checkoutDigest: string; money: {currency: string; minorUnits: string}; quoteExpiresAt: string};
type Action = {actionId: string; version: number; state: string; expiresAt: string};
const element = <T extends HTMLElement>(id: string): T => {
  const found = document.getElementById(id); if (!found) throw new Error("Missing page control."); return found as T;
};
let reviewed: Checkout | undefined;
let challenge: Action | undefined;
let currentIntent = "";
const pendingKeys = new Map<string, string>();
function message(text: string): void { element("message").textContent = text; }
async function api(path: string, body?: Json): Promise<Json> {
  const token = element<HTMLInputElement>("token").value.trim();
  if (!token) throw new Error("Enter the trusted UI session to continue.");
  const identity = path + JSON.stringify(body);
  const headers: Record<string, string> = {Authorization: `Bearer ${token}`};
  if (body) { headers["Content-Type"] = "application/json"; if (!pendingKeys.has(identity)) pendingKeys.set(identity, crypto.randomUUID()); headers["Idempotency-Key"] = pendingKeys.get(identity)!; }
  let response: Response;
  try { response = await fetch(`/internal/v0.1${path}`, {method: body ? "POST" : "GET", headers, body: body ? JSON.stringify(body) : undefined, cache: "no-store", credentials: "omit"}); }
  catch { throw new Error("Connection lost. Refresh status before continuing; the payment outcome may be unresolved."); }
  const data = await response.json() as Json;
  if (!response.ok) { const error = data.error as {message?: string}; throw new Error(error?.message ?? "The request could not be completed."); }
  return data;
}
function detail(label: string, value: string): void {
  const dt = document.createElement("dt"); dt.textContent = label;
  const dd = document.createElement("dd"); dd.textContent = value;
  element("details").append(dt, dd);
}
async function load(): Promise<void> {
  const id = element<HTMLInputElement>("intent").value.trim();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) throw new Error("Enter a valid payment intent UUID.");
  currentIntent = id; reviewed = undefined; challenge = undefined;
  element("approve").hidden = true; element("challenge").hidden = true;
  const status = await api(`/intents/${id}`);
  element("payment").hidden = false; element("state").textContent = String(status.state);
  element("details").replaceChildren();
  const money = status.money as {currency: string; minorUnits: string} | null;
  element("amount").textContent = money ? `${money.currency} ${(Number(money.minorUnits) / 100).toFixed(2)}` : "Awaiting quote";
  detail("Exposure", String(status.exposureState));
  if (status.state === "QUOTED" || status.state === "APPROVED") {
    // Retrieve review data directly from the authenticated core, never from graph/browser callbacks.
    reviewed = await api(`/intents/${id}/checkout`) as unknown as Checkout;
    element("amount").textContent = `${reviewed.money.currency} ${(Number(reviewed.money.minorUnits) / 100).toFixed(2)}`;
    detail("Merchant", reviewed.merchantId); detail("Quote revision", String(reviewed.checkoutRevision));
    detail("Quote expires", reviewed.quoteExpiresAt);
    element("approve").hidden = reviewed.state !== "QUOTED";
  }
  if (status.state === "WAITING_ACTION" && typeof status.actionId === "string") {
    challenge = await api(`/actions/${status.actionId}`) as unknown as Action;
    element("challenge").hidden = challenge.state !== "OPEN";
  }
  element("references").textContent = JSON.stringify(status, null, 2);
  message(status.reconciliationRequired ? "Outcome unresolved. The reservation remains held while the existing operation is reconciled." : status.state === "SUCCEEDED" ? "Synthetic payment completed. No real money moved." : "Payment status refreshed from the core.");
}
async function approve(): Promise<void> {
  if (!reviewed || reviewed.intentId !== currentIntent || reviewed.state !== "QUOTED") return;
    await api(`/intents/${currentIntent}/approve`, {expectedVersion: reviewed.version, checkoutRevision: reviewed.checkoutRevision, checkoutDigest: reviewed.checkoutDigest, authorityReference: reviewed.money.currency === "USD" ? "fixture-grant-usd" : "fixture-grant-1"});
  await load(); message("Checkout approved. Resume the agent workflow to continue execution.");
}
async function complete(decision: "approve" | "deny"): Promise<void> {
  if (!challenge || challenge.state !== "OPEN") return;
  await api(`/actions/${challenge.actionId}/complete`, {expectedVersion: challenge.version, decision});
  await load();
}
function bind(id: string, task: () => Promise<void>): void {
  element<HTMLButtonElement>(id).addEventListener("click", async () => {
    const controls = Array.from(document.querySelectorAll<HTMLInputElement | HTMLButtonElement>("button,input")); controls.forEach(control => control.disabled = true);
    try { await task(); } catch (error) { message(error instanceof Error ? error.message : "The request failed."); }
    finally { controls.forEach(control => control.disabled = false); }
  });
}
element("token").addEventListener("input", () => { reviewed = undefined; challenge = undefined; element("payment").hidden = true; pendingKeys.clear(); });
element("intent").addEventListener("input", () => { reviewed = undefined; challenge = undefined; currentIntent = ""; element("payment").hidden = true; });
bind("load", load); bind("refresh", load); bind("approve", approve); bind("confirm", () => complete("approve")); bind("deny", () => complete("deny"));
