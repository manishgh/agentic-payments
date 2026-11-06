const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { webcrypto } = require('node:crypto');

function page(respond) {
  const elements = new Map();
  const ids = ['token','intent','load','message','payment','state','amount','details','approve','refresh','challenge','confirm','deny','references'];
  for (const id of ids) elements.set(id, {value:'',textContent:'',hidden:false,disabled:false,handlers:{},children:[],
    addEventListener(name, fn) { this.handlers[name] = fn; },
    append(...items) { this.children.push(...items); }, replaceChildren() { this.children = []; }});
  elements.get('token').value = 'CANARY_TRUSTED_SESSION_ONLY_IN_REQUEST_HEADER';
  elements.get('intent').value = '10000000-0000-4000-8000-000000000001';
  const calls = [];
  const context = vm.createContext({document:{getElementById:id=>elements.get(id),createElement:()=>({textContent:''}),querySelectorAll:()=>[...elements.values()]},
    crypto:webcrypto,fetch:async (url, options)=>{calls.push({url,options}); return {ok:true,json:async()=>respond(url,options)};},console});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../dist/app.js'),'utf8'), context);
  return {elements,calls,click: id=>elements.get(id).handlers.click(),input:id=>elements.get(id).handlers.input()};
}
const intent = '10000000-0000-4000-8000-000000000001';
function response(url) {
  return url.endsWith('/checkout') ? {intentId:intent,state:'QUOTED',version:3,merchantId:'merchant-demo',checkoutRevision:2,checkoutDigest:'sha256:new-usd-checkout',money:{currency:'USD',minorUnits:'1250'},quoteExpiresAt:'2026-11-06T10:15:00Z'}
    : {intentId:intent,state:'QUOTED',version:2,money:{currency:'EUR',minorUnits:'1250'},exposureState:'NONE',reconciliationRequired:false};
}
test('approval displays money from the exact trusted checkout revision even if status was stale',async()=>{
  const ui=page(response); await ui.click('load');
  assert.equal(ui.elements.get('amount').textContent,'USD 12.50');
  await ui.click('approve');
  const command=ui.calls.find(call=>call.url.endsWith('/approve'));
  const body=JSON.parse(command.options.body);
  assert.equal(body.checkoutDigest,'sha256:new-usd-checkout'); assert.equal(body.expectedVersion,3);
  assert.equal(body.authorityReference,'fixture-grant-usd');
  assert.ok(!JSON.stringify(body).includes('CANARY'));
});
test('changing the typed intent invalidates the reviewed approval',async()=>{
  const ui=page(response); await ui.click('load');
  ui.elements.get('intent').value='20000000-0000-4000-8000-000000000002'; ui.input('intent');
  await ui.click('approve'); assert.equal(ui.calls.filter(call=>call.url.endsWith('/approve')).length,0);
  assert.equal(ui.elements.get('payment').hidden,true);
});
test('transport uncertainty preserves the idempotency key for the same approval',async()=>{
  let attempts=0;
  const ui=page((url,options)=>{if(url.endsWith('/approve') && ++attempts===1) throw new Error('lost'); return response(url);});
  await ui.click('load'); await ui.click('approve'); await ui.click('approve');
  const commands=ui.calls.filter(call=>call.url.endsWith('/approve'));
  assert.equal(commands.length,2); assert.equal(commands[0].options.headers['Idempotency-Key'],commands[1].options.headers['Idempotency-Key']);
});
