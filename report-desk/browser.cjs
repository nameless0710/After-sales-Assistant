// Read and operate the visible ERP page only. Login stays inside the dedicated browser.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('./driver/node_modules/playwright-core');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
let q = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let out = path.dirname(process.argv[2]);
const home = process.env.LOCALAPPDATA || process.env.HOME;
const prefs = JSON.parse(fs.readFileSync(path.join(__dirname, 'browser-settings.json'), 'utf8').replace(/^\uFEFF/, ''));
const cfg = prefs.controls;
const fixture = process.argv.includes('--fixture');
const audit = process.argv.includes('--audit-grain');
const catalog = process.argv.includes('--catalog');
const warehouses = process.argv.includes('--warehouses');
function emit(s) { process.stdout.write(s + '\n'); }
function checkedURL(raw) {
  const url = new URL(raw);
  if (fixture && url.hostname === '127.0.0.1') return raw;
  if (url.protocol !== 'https:' || !/(^|\.)(erp321|jushuitan)\.com$/.test(url.hostname))
    throw Error('请使用聚水潭 HTTPS 页面地址');
  return raw;
}
function allowed(frame) {
  if(frame.url()==='about:blank' && frame.parentFrame())return allowed(frame.parentFrame());
  try { checkedURL(frame.url()); return true; } catch { return false; }
}
async function shown(frame) {
  for(let f=frame;f.parentFrame();f=f.parentFrame()){
    const el=await f.frameElement();
    try{if(!await el.isVisible())return false;}finally{await el.dispose();}
  }
  return true;
}
async function orderFrame(frame) {
  if(!frame.parentFrame())return false;
  const pathname=new URL(frame.url()).pathname;
  const items=(q.bulk || audit) && current==='sales';
  const pathPattern=items?/\/detail_(?:new_sum|order_?(?:sku|item|goods)|(?:sku|item)_?order)\.aspx$/i:/\/detail_order\.aspx$/i;
  if(pathPattern.test(pathname))return true;
  const host=await frame.frameElement();
  try{
    if(pathPattern.test('/'+(await host.getAttribute('data-url')||'')))return true;
    if(items && !/\/detail_order\.aspx$/i.test(pathname)){
      return (await frame.getByText('商品编码',{exact:true}).filter({visible:true}).count())>0 &&
        (await frame.getByText('内部订单号',{exact:true}).filter({visible:true}).count())>0;
    }
    return false;
  }
  finally{await host.dispose();}
}
const reportKeys=new Set(['reportCount','emptyReport','shippedSum','returnedSum','shippedSumHeader','returnedSumHeader','export']);
const pickerKeys=new Set(['skuInput','search','pickerCount','skuHeader','pickAll','pickerNext','selectedCount','confirm','cancelPicker','enabled']);
async function locations(ctx){
  return [...new Set(ctx.pages().flatMap(p=>p.frames()).map(f=>{
    try{const u=new URL(f.url());return u.protocol.startsWith('http')?u.origin+u.pathname:u.protocol;}
    catch{return '';}
  }))].join(' · ');
}
async function inspect(ctx, label) {
  if(process.env.AFTERSALES_UI_DIAG!=='1')return;
  const words={picker:'包含商品',skuInput:'商品编码',search:'搜索',pickerCount:'共',skuHeader:'商品编码',pickAll:'商品编码',
    selectedCount:'已选商品',enabled:'启用',reportCount:'共',shippedSumHeader:'实发数量',returnedSumHeader:'退货数量',export:'导出',generate:'生成报表'};
  for(const page of ctx.pages())for(const f of page.frames().filter(allowed)){
    if(!await shown(f).catch(()=>false))continue;
    const description=await f.evaluate(({word,label})=>{
      const sig=e=>{
        if(!e)return '';
        return [e.tagName,e.id?'#'+e.id:'',typeof e.className==='string'?'.'+e.className.trim().replaceAll(' ','.'):'',e.type?'['+e.type+']':'',e.placeholder?'[placeholder='+e.placeholder+']':''].join('');
      };
      if(label==='skuInput')return [...document.querySelectorAll('input')].filter(e=>e.offsetWidth && e.placeholder).map(sig).slice(0,28);
      if(!word)return [];
      const els=[...document.querySelectorAll('body *')].filter(el=>el.childElementCount===0 && el.offsetWidth && el.textContent.trim().includes(word)).slice(0,4);
      return els.map(el=>{
        const p=el.parentElement;
        return [sig(el),sig(p),sig(p?.parentElement),...Array.from(p?.parentElement?.querySelectorAll('input,button,select,a,span,i,div')||[]).slice(0,24).map(sig)].join(' ');
      });
    },{word:words[label],label}).catch(()=>[]);
    if(description.length)emit('页面控件 '+label+' '+description.join(' | '));
  }
}
async function find(ctx, label, factory, ms = 15000, optional = false) {
  let end = Date.now() + ms;
  do {
    const frames=ctx.pages().slice().reverse().flatMap(page=>page.frames()).filter(allowed);
    const dialogs=pickerKeys.has(label)?frames.filter(f=>f.url().includes('/goods-selector/')):[];
    for (const f of (dialogs.length?dialogs:frames)) {
      if(!await shown(f).catch(()=>false))continue;
      if(reportKeys.has(label) && !await orderFrame(f).catch(()=>false))continue;
      const l = cfg[label] ? f.locator(cfg[label]) : factory(f);
      const visible = l.filter({visible:true});
      const count = await visible.count().catch(() => 0);
      if (count === 1) return visible.first();
    }
    await wait(220);
  } while (Date.now() < end);
  if (!optional) {await inspect(ctx,label);throw Error('无法唯一定位「'+label+'」。当前页面 '+await locations(ctx));}
  return null;
}
const exact = name => f => f.getByText(name.length===2 ? new RegExp('^'+name[0]+'\\s*'+name[1]+'$') : name, {exact:true});
function rowControl(f, label, selector) {
  return f.getByText(label,{exact:true}).locator('..').locator(selector);
}
async function setDate(input, value) {
  await input.fill(value); await input.press('Tab');
  if ((await input.inputValue()).replaceAll('/','-') !== value) throw Error('日期输入未生效');
}
async function settings(ctx) {
  await (await find(ctx,'reset',exact('重置查询'))).click();
  await wait(900);
  const items=(q.bulk || audit) && current==='sales';
  const tab = await find(ctx,items?'orderItemTab':'orderTab',f=>f.getByText(items?/^明细\s*[(（]订单商品[)）]$/:/^明细\s*[(（]订单[)）]$/));
  await tab.click();
  await wait(450);
  const type = await find(ctx,'dateKind',exact('发货时间'),1800,true);
  if (!type) {
    await (await find(ctx,'dateMenu', f=>f.getByText(/^(订单时间|付款时间|订单日期)$/))).click();
    await (await find(ctx,'shipTime',exact('发货时间'))).click();
  }
  for (const [key,index,value] of [['start',0,q.start],['end',1,q.end]]) {
    const control=await find(ctx,key,f=>f.locator(index?'#order_date_end':'#order_date_begin'),350,true) ||
      await find(ctx,key,f=>f.locator('input:visible').filter({}).and(
      f.locator('input[type=date],input[placeholder*=日期],input.Wdate,input[value^="20"]')).nth(index));
    await setDate(control,value);
  }
  for (const [key,label,on] of [['split','组合商品拆分',true],['physical','实体编码拆分',false],['exclude','排除发货前取消商品',true]]) {
    const box=await find(ctx,key,f=>f.getByRole('checkbox',{name:label,exact:true}),500,true)
      || await find(ctx,key,f=>f.getByText(label,{exact:true}).locator('xpath=preceding-sibling::input[@type="checkbox"][1]'));
    await box.setChecked(on);
    if(await box.isChecked() !== on) throw Error(label+'设置未生效');
  }
  if(q.warehouse !== '所有仓库') {
    const search=await find(ctx,'whSearch',f=>f.locator('#search_wms_co_id'),500,true);
    if(search){
      await search.fill(q.warehouse);await search.press('Enter');
      const label=await find(ctx,'whName',exact(q.warehouse),5000,true);
      if(!label){
        emit('仓库筛选控件 '+await search.evaluate(e=>e.parentElement.parentElement.outerHTML
          .replace(/\s(?:value|style|on\w+)="[^"]*"/gi,'').slice(0,1100)));
        throw Error('仓库候选项中没有「'+q.warehouse+'」');
      }
      const check=label.locator('xpath=ancestor-or-self::*[.//input[@type="checkbox"]][1]').locator('input[type=checkbox]');
      if(await check.count()===1){
        await check.check();if(!await check.isChecked())throw Error('仓库选择未生效');
      }else await label.click();
      return;
    }
    const wh=await find(ctx,'warehouse',f=>rowControl(f,'发货仓','select,input,[role=combobox]'));
    if(await wh.evaluate(el=>el.tagName==='SELECT')) await wh.selectOption({label:q.warehouse});
    else { await wh.click(); await (await find(ctx,'warehouseOption',exact(q.warehouse))).click(); }
    const selected = await wh.evaluate(el=>el.selectedOptions?.[0]?.textContent||el.value||el.parentElement.innerText);
    if(!selected.includes(q.warehouse)) throw Error('发货仓未选中');
  }
}
const clean=s=>s.trim().toLowerCase();
async function pick(ctx, key, catalogOnly=false, exactly=null) {
  const legacy=await find(ctx,'skuPickerInput',f=>f.locator('input#sku_id.input_picker'),450,true);
  const trigger=legacy || await find(ctx,'picker',f=>rowControl(f,'包含商品','button,[role=button],input[type=button]').first(),700,true)
    || await find(ctx,'picker',f=>f.getByText('包含商品',{exact:true}).locator('xpath=ancestor::*[self::tr or self::li or self::div][.//input][1]').locator('button,input[type=button],a,span,i').filter({hasText:/^(\.\.\.|…|⋯)$/}).first());
  if(exactly && legacy && (await legacy.getAttribute('title')||'').includes('直接输入')){
    const text=exactly.join(','),page=legacy.page();
    const respond=async d=>{if(d.type()==='prompt')await d.accept(text);else await d.dismiss();};
    page.on('dialog',respond);
    try{
      await legacy.click({modifiers:['Control'],position:{x:8,y:8}});
      await wait(300);
      if(await legacy.inputValue()!==text){
        const popup=await find(ctx,'exactInput',f=>f.locator('textarea:visible').or(f.locator('[role=dialog] input[type=text]:visible')),1000,true);
        if(popup){await popup.fill(text);await (await find(ctx,'exactConfirm',exact('确定'))).click();}
        else if(await legacy.isEditable()){await legacy.fill(text);await legacy.press('Tab');}
      }
      const actual=(await legacy.inputValue()).split(/[,，]/).map(clean);
      if(actual.length===exactly.length && exactly.every(s=>actual.includes(clean(s))))return exactly;
      throw Error('精确录入的报表商品条件未生效');
    }finally{page.off('dialog',respond);}
  }
  if(legacy){
    await legacy.scrollIntoViewIfNeeded();
    await legacy.click({position:{x:8,y:8},timeout:6000});
  }else await trigger.click();
  const byPlaceholder=f=>f.getByPlaceholder('商品编码',{exact:true});
  let field=await find(ctx,'skuInput',byPlaceholder,2000,true);
  if(!field)field=await find(ctx,'skuInput',byPlaceholder);
  if(legacy && !exactly){
    // Let the initial unfiltered total finish before issuing a second search.
    await find(ctx,'pickerCount',f=>f.getByText(/^共\s*[1-9][\d,]*\s*条/),16000,true);
  }
  if(exactly){
    for(const target of exactly){
      await field.fill(target);
      await (await find(ctx,'search',exact('搜索'))).click();
      const head=await find(ctx,'skuHeader',f=>f.locator('th,[role=columnheader]').filter({hasText:/^商品编码$/}));
      const index=await head.evaluate(e=>[...e.parentElement.children].indexOf(e));
      const row=await find(ctx,'auditSku',f=>f.locator('tbody tr').filter({has:f.locator('td:nth-child('+(index+1)+')').filter({hasText:new RegExp('^\\s*'+target.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'\\s*$','i')})}),5000,true);
      if(!row){
        const el=await field.elementHandle(),frame=await el.ownerFrame();await el.dispose();
        const choices=await frame.locator('tbody tr td:nth-child('+(index+1)+')').allTextContents();
        throw Error('对账 SKU '+target+' 在启用商品选择器中未精确匹配；可见编码 '+choices.slice(0,4).join(' / '));
      }
      await row.locator('input[type=checkbox]').check();
    }
    await find(ctx,'selectedCount',f=>f.getByText(new RegExp('^已选商品[（(]'+exactly.length+'[）)]$')));
    await (await find(ctx,'confirm',exact('确定'))).click();
    const actual=(await legacy.inputValue()).split(/[,，]/).map(clean);
    if(actual.length!==exactly.length || !exactly.every(s=>actual.includes(clean(s))))throw Error('报表上的精确 SKU 与对账条件不一致');
    return exactly;
  }
  await field.fill(key);
  if(process.env.AFTERSALES_UI_DIAG==='1'){
    const el=await field.elementHandle();const popup=await el.ownerFrame();await el.dispose();
    const summary=await popup.evaluate(()=>[...document.querySelectorAll('button,thead th,[role=columnheader],.ant-pagination-total-text,.ant-select-selection-item')]
      .filter(el=>el.offsetWidth).slice(0,55).map(el=>el.tagName+'.'+el.className+'='+el.textContent.trim().slice(0,30)).join(' | '));
    emit('商品弹窗 '+summary);
  }
  await (await find(ctx,'search',exact('搜索'))).click();
  const info=await find(ctx,'pickerCount',f=>f.getByText(/^共\s*[\d,]+\s*条/));
  const header=await find(ctx,'skuHeader',f=>f.locator('th,[role=columnheader]').filter({hasText:/^商品编码$/}));
  const wrapper=header.locator('xpath=ancestor::*[contains(@class,"art-table-wrapper") or contains(@class,"ant-table-wrapper") or @role="grid"][1]');
  const grid=cfg.skuGrid ? header.locator('xpath=ancestor::body').locator(cfg.skuGrid) :
    (await wrapper.count()?wrapper:header.locator('xpath=ancestor::table[1]'));
  const extract=async()=>grid.evaluate((root, selectors)=>{
    // A fixed header can be in a separate table from the scrollable body.
    const scope=root.closest('.ant-table-wrapper')||root;
    const headers=[...root.querySelectorAll('thead th,[role=columnheader]')];
    const i=headers.findIndex(el=>el.textContent.trim()==='商品编码');
    if(i<0) return [];
    return [...scope.querySelectorAll(selectors.skuRows || 'tbody tr,[role=row]')].map(row=>{
      const cell=row.querySelectorAll('td,[role=gridcell],[role=cell]')[i];
      return (cell?.getAttribute('title') || cell?.querySelector('[title]')?.getAttribute('title') || cell?.textContent || '').trim();
    }).filter(Boolean);
  },cfg);
  const getCount=async()=>Number((await info.innerText()).match(/共\s*([\d,]+)\s*条/)[1].replaceAll(',',''));
  // Wait for matching results. A previous, unfiltered grid must never be accepted.
  let end=Date.now()+15000, values=[];
  do {
    values=await extract();
    if(values.length && values.every(s=>clean(s).includes(clean(key)))) break;
    await wait(200);
  } while(Date.now()<end);
  if(!values.length || !values.every(s=>clean(s).includes(clean(key)))){
    emit('商品表 '+await header.evaluate(el=>{let n=el;const a=[];while(n&&a.length<12){a.push(n.tagName+'.'+n.className);n=n.parentElement;}return a.join(' / ');}));
    throw Error('未取得匹配的启用商品：'+key+'；表内读取 '+values.length+' 项，分页显示 '+await info.innerText());
  }
  const status=await find(ctx,'skuStatus',f=>f.getByPlaceholder('商品状态',{exact:true}),500,true);
  if(!status || (await status.inputValue())!=='启用'){
    await find(ctx,'enabled',f=>f.getByText('启用',{exact:true}).or(f.locator('select').filter({has:f.locator('option:checked',{hasText:'启用'})})));
  }
  // The picker issues separate row and count requests. Wait for the count
  // of this search even when its rows are already visible.
  let total=await getCount(),stable=Date.now();
  for(let deadline=Date.now()+12000;Date.now()<deadline;){
    await wait(200);const n=await getCount();
    if(n!==total){stable=Date.now();total=n;}
    if(total>=values.length && Date.now()-stable>1200)break;
  }
  const codes=new Map();
  const perPageEl=await find(ctx,'perPage',f=>f.locator('.ant-select-selection-item').filter({hasText:/^\d+$/}),350,true);
  const perPage=perPageEl?Number(await perPageEl.innerText()):50;
  if(total<1) throw Error('没有匹配的启用商品');
  for(let page=1;page<=total;page++){
    const scroll=async(reset)=>grid.evaluate((root,reset)=>{
      const scope=root.closest('.ant-table-wrapper')||root;
      const el=[...scope.querySelectorAll('*')].find(x=>x.clientHeight>0 && x.scrollHeight>x.clientHeight+2 && /auto|scroll/.test(getComputedStyle(x).overflowY));
      if(!el)return false;
      const old=el.scrollTop; el.scrollTop=reset?0:old+Math.max(1,el.clientHeight-30); return old!==el.scrollTop;
    },reset);
    await scroll(true);
    const before=codes.size;
    do {
      await wait(100);
      for(const sku of await extract()) {
        if(!clean(sku).includes(clean(key)) || sku.includes('…'))throw Error('商品表含意外或截断的编码');
        codes.set(clean(sku),sku);
      }
    } while(await scroll(false));
    const pageItems=codes.size-before;
    if(pageItems>0 && pageItems<perPage && codes.size!==total){
      const until=Date.now()+12000;
      do{total=await getCount();if(total===codes.size)break;await wait(200);}while(Date.now()<until);
      if(total!==codes.size)throw Error('商品行与分页总数尚未一致，请重新搜索');
    }
    if(!catalogOnly){
      const all=await find(ctx,'pickAll',()=>grid.locator('thead input[type=checkbox],[role=columnheader] input[type=checkbox]'));
      if(!await all.isChecked()) await all.click();
    }
    if(codes.size===total)break;
    emit('商品分页显示 '+total+' 项，已读 '+codes.size+' 项');
    if(codes.size===before || codes.size>total)throw Error('商品分页重复或数量异常');
    await (await find(ctx,'pickerNext',f=>f.locator('.ant-pagination-next:not(.ant-pagination-disabled) button,[aria-label="下一页"]:not([disabled])')
      .or(f.locator('button:not([disabled])').filter({has:f.locator('[aria-label="right"],.anticon-right')})))).click();
    let advanced=false;
    for(const deadline=Date.now()+20000;Date.now()<deadline;){
      await wait(150);
      const visible=await extract();
      if(visible.some(s=>!codes.has(clean(s)))){advanced=true;break;}
    }
    if(!advanced)throw Error('商品分页未加载新行');
  }
  if(codes.size!==total) throw Error('商品清单不完整');
  if(catalogOnly)await (await find(ctx,'cancelPicker',exact('取消'))).click();
  else{
    await find(ctx,'selectedCount', f=>f.getByText(new RegExp('^已选商品[（(]'+total+'[）)]$')));
    await (await find(ctx,'confirm',exact('确定'))).click();
    if(legacy){
      const must=codes;
      const until=Date.now()+10000;let selected=[];
      do{selected=(await legacy.inputValue()).split(/[,，]/).map(clean).filter(Boolean);
        if(selected.length===must.size && selected.every(s=>must.has(s)))break;
        await wait(150);
      }while(Date.now()<until);
      if(selected.length!==must.size || !selected.every(s=>must.has(s))){
        if((await legacy.getAttribute('title')||'').includes('直接输入')){
          emit('已读取 '+must.size+' 项，报表回填 '+selected.length+' 项；使用页面的精确录入功能重新核对清单');
          return await pick(ctx,key,false,[...codes.values()]);
        }
        throw Error('确认后，报表实际商品筛选与弹窗清单不一致');
      }
    }
  }
  return [...codes.values()];
}
async function dataDate(ctx){
  const day=await find(ctx,'dataDate',f=>f.getByText(/数据更新至[：:]\s*\d{4}-\d{2}-\d{2}/),2500,true);
  return day?(await day.innerText()).match(/\d{4}-\d{2}-\d{2}/)?.[0]||null:null;
}
async function exportReport(ctx, name, skus) {
  if(!fixture){
    for(const [key,id,value] of [['start','order_date_begin',q.start],['end','order_date_end',q.end]]){
      const date=await find(ctx,key,f=>f.locator('#'+id));
      if((await date.inputValue()).replaceAll('/','-')!==value)await setDate(date,value);
    }
    await wait(500);
    for(const [key,id,value] of [['start','order_date_begin',q.start],['end','order_date_end',q.end]]){
      const date=await find(ctx,key,f=>f.locator('#'+id));
      if((await date.inputValue()).replaceAll('/','-')!==value)throw Error('生成报表前日期条件发生变化');
    }
  }
  const button=await find(ctx,'generate',exact('生成报表'));
  // A restored ERP tab can show yesterday's table. Observe only the visible
  // order-detail grid. Filter/sidebar mutations do not prove the report refreshed.
  const prior=await find(ctx,'reportCount',f=>f.getByText(/共\s*[\d,]+\s*条记录/),350,true);
  const empty=f=>f.getByText(/^(暂无数据|没有符合条件的数据|未查询到(?:符合条件的)?(?:数据|记录)|没有查到数据|未找到符合条件的记录|无数据|没有记录)[。！!]?$/);
  const oldEmpty=await find(ctx,'emptyReport',empty,250,true);
  const oldNode=oldEmpty?await oldEmpty.elementHandle():null;
  let priorFrame=null;
  if(prior){
    const handle=await prior.elementHandle();priorFrame=await handle.ownerFrame();await handle.dispose();
    await priorFrame.evaluate(()=>{
      window.__afterSalesGridChanged=false;
      window.__afterSalesObserver?.disconnect();
      window.__afterSalesObserver=new MutationObserver(ms=>{
        if(ms.some(m=>m.type==='characterData'?m.target.parentElement?.closest('tbody,[role=rowgroup]'):
          [...m.addedNodes,...m.removedNodes].some(n=>n.nodeType===1 && (n.matches('tr,tbody,[role=row]') || n.querySelector('tr,[role=row]'))))){
          window.__afterSalesGridChanged=true;
        }
      });window.__afterSalesObserver.observe(document.body,{childList:true,subtree:true,characterData:true});
    });
  }
  await button.click();
  if(priorFrame){
    const until=Date.now()+90000;
    while(Date.now()<until && !priorFrame.isDetached() && !await priorFrame.evaluate(()=>window.__afterSalesGridChanged!==false).catch(()=>false))await wait(200);
    if(Date.now()>=until)throw Error('未观察到订单明细重新生成，已保留旧结果');
  }
  let countEl=null;
  const limit=Date.now()+90000;
  while(Date.now()<limit){
    countEl=await find(ctx,'reportCount',f=>f.getByText(/共\s*[\d,]+\s*条记录/),300,true);
    if(countEl)break;
    const noRows=await find(ctx,'emptyReport',empty,300,true);
    if(!noRows || (oldNode && await oldNode.isVisible().catch(()=>false)))continue;
    const handle=await noRows.elementHandle(),f=await handle.ownerFrame();await handle.dispose();
    await wait(600);
    if(await noRows.isVisible() && !await f.locator('div.wait:visible,.ant-spin-spinning:visible,[aria-busy=true]:visible').count()){
      if(oldNode)await oldNode.dispose();
      emit('网页明确显示没有符合条件的记录');
      return {count:0,shipped:0,returned:0,reportDate:await dataDate(ctx),skus};
    }
  }
  if(oldNode)await oldNode.dispose();
  if(!countEl)throw Error('订单报表未显示记录总数或明确的空结果');
  const getNumber=s=>Number(s.replaceAll(',',''));
  let count=-1, stableSince=Date.now();
  const parent=(await countEl.elementHandle());const active=await parent.ownerFrame();await parent.dispose();
  let settled=false;
  for(let deadline=Date.now()+90000;Date.now()<deadline;){
    const n=getNumber((await countEl.innerText()).match(/共\s*([\d,]+)\s*条/)[1]);
    const loading=await active.locator('div.wait:visible,.ant-spin-spinning:visible,[aria-busy=true]:visible').count();
    if(n!==count||loading){count=n;stableSince=Date.now();}
    if(!loading && Date.now()-stableSince>1200 && (n>0 || await empty(active).filter({visible:true}).count())){settled=true;break;}
    await wait(200);
  }
  if(!settled)throw Error('订单明细未明确完成加载，已放弃发布');
  if(q.bulk && count>prefs.maxBatchRows && q.start!==q.end)throw Error('单批行数 '+count+'，拆分日期后导出');
  async function sum(key, label) {
    if(cfg[key]) return getNumber((await (await find(ctx,key,exact(label))).innerText()).trim() || '0');
    const h=await find(ctx,key+'Header',exact(label));
    const text=await h.evaluate((el,label)=>{
      const cell=el.closest('th,[role=columnheader],td'); if(!cell)return null;
      const headers=[...cell.parentElement.children], idx=headers.indexOf(cell);
      const root=cell.closest('.ant-table-wrapper,[role=grid]') || cell.closest('table')?.parentElement?.parentElement;
      if(!root || idx<0)return null;
      const row=[...root.querySelectorAll('tfoot tr,tr,[role=row]')].find(r=>/^(##|合计|总计)$/.test(r.firstElementChild?.textContent.trim()));
      return row?.children[idx]?.textContent.trim() ?? null;
    },label);
    if(text===null || !/^[\d,.-]*$/.test(text))throw Error('未找到完整报表的「'+label+'」合计，请配置 '+key);
    return getNumber(text||'0');
  }
  const shipped=name!=='reships' && count>0 ? await sum('shippedSum','实发数量') : 0;
  const returned=name!=='reships' && count>0 ? await sum('returnedSum','退货数量') : 0;
  emit('网页全表 '+count+' 条'+(name!=='reships'?'，实发 '+shipped+' 件，退货 '+returned+' 件':''));
  if(!Number.isSafeInteger(shipped)||!Number.isSafeInteger(returned))throw Error('报表合计无效');
  const reportDate=await dataDate(ctx);
  if(count>0) {
    const dl=await find(ctx,'export',f=>f.getByText(/^导出\s*EXCEL$/i));
    const page=dl.page();
    const onDialog=async d=>{
      if(/导出|下载|excel/i.test(d.message()) && !/删除|清空/.test(d.message())){
        emit('网页导出提示 '+d.message().slice(0,150));await d.accept();
      }else await d.dismiss();
    };
    page.on('dialog',onDialog);
    const download=new Promise((resolve,reject)=>{
      const pages=ctx.pages(),timer=setTimeout(()=>done(Error('未收到正式 Excel 下载；请检查报表下载提示')),120000);
      function done(error,value){clearTimeout(timer);ctx.off('page',bind);for(const p of pages)p.off('download',receive);error?reject(error):resolve(value);}
      function receive(d){done(null,d);} function bind(p){if(!pages.includes(p))pages.push(p);p.on('download',receive);}
      for(const p of pages)p.on('download',receive);ctx.on('page',bind);
    });
    let file;
    try{[file]=await Promise.all([download,(async()=>{
      await dl.click();
      const all=await find(ctx,'exportAll',f=>f.getByText(/^(导出全部|全部导出|导出所有页)$/),1200,true);
      if(all)await all.click();
      const modal=await find(ctx,'exportDialog',f=>f.locator('[role=dialog],.ant-modal,.messager-window,.ui-dialog,.layui-layer').filter({hasText:/导出|下载|Excel/i}),600,true);
      if(modal){
        emit('网页导出提示 '+(await modal.innerText()).replace(/\s+/g,' ').slice(0,180));
        const ok=modal.getByRole('button',{name:/^(确\s*定|确\s*认|开始导出|立即导出|继续导出)$/});
        if(await ok.count()===1)await ok.click();
      }
      if(q.bulk && count>5000){
        for(const f of page.frames().filter(allowed)){
          const hint=await f.getByText(/(请|前往|可在|已提交|正在).*?(下载中心|导出中心|导出任务|后台导出)/).filter({visible:true}).first().textContent({timeout:120}).catch(()=>null);
          if(hint)emit('导出进度 '+hint.trim().slice(0,100));
        }
      }
    })()]);}finally{page.off('dialog',onDialog);}
    if(!file.suggestedFilename().toLowerCase().endsWith('.xlsx'))throw Error('导出文件格式应为 xlsx');
    await file.saveAs(path.join(out,name+'.xlsx'));
    if(await file.failure())throw Error('Excel 下载失败');
  }
  return {count,shipped,returned,reportDate,skus};
}
async function ready(ctx, page) {
  const start=Date.now(),end=start+(fixture?5000:300000);
  let menuOpened=false, prompted=false, lastNavigation=0;
  do{
    const shell=await find(ctx,'generate',exact('生成报表'),350,true);
    if(shell)return;
    const link=await find(ctx,'reportLink',exact('销售主题分析'),220,true);
    if(link && Date.now()-lastNavigation>15000){await link.click();lastNavigation=Date.now();await wait(1200);continue;}
    if(!menuOpened){
      const reports=await find(ctx,'reportsMenu',exact('报表'),220,true);
      if(reports){await reports.hover();await wait(300);
        const item=await find(ctx,'reportLink',exact('销售主题分析'),400,true);
        if(item){await item.click();lastNavigation=Date.now();}else await reports.click();menuOpened=true;continue;}
    }
    if(!prompted && (Date.now()-start>25000 || ctx.pages().some(p=>/\/login(?:\.aspx|\/|$)/i.test(new URL(p.url()).pathname)))){
      emit('正在等待销售主题分析页面。若窗口显示登录页，请正常登录；已登录可从「报表」打开「销售主题分析」。');prompted=true;
    }
    await wait(400);
  }while(Date.now()<end && !page.isClosed());
  throw Error('等待登录或打开报表超时，查询条件已保留');
}
let current='';
let confirmedReshipCodes=null;
async function runTask(ctx,page,url){
  try{return await collectTask(ctx,page);}
  catch(e){
    if(!/locator\.|elementHandle\.|无法唯一定位|框架已|分页总数尚未一致|商品分页未加载新行|商品分页重复或数量异常|实际商品筛选与弹窗清单不一致|精确录入的报表商品条件未生效/.test(e.message))throw e;
    emit('页面控件在查询过程中发生变化，重新打开报表并重试本段');
    confirmedReshipCodes=null;
    await page.goto(url,{waitUntil:'domcontentloaded',timeout:60000});
    return await collectTask(ctx,page);
  }
}
async function main(){
  const exe=process.env.AFTERSALES_CHROME || ['PROGRAMFILES','PROGRAMFILES(X86)','LOCALAPPDATA'].flatMap(k=>['Google/Chrome/Application/chrome.exe','Microsoft/Edge/Application/msedge.exe'].map(p=>path.join(process.env[k]||'',p))).find(p=>fs.existsSync(p));
  if(!exe)throw Error('未找到 Chrome 或 Edge，可用 AFTERSALES_CHROME 指定可执行文件');
  const ctx=await chromium.launchPersistentContext(fixture?path.join(out,'profile'):path.join(home,'AfterSalesDesk','Browser'),{executablePath:exe,headless:fixture,acceptDownloads:true,viewport:{width:1600,height:960},timeout:25000});
  try{
    const page=ctx.pages()[0]||await ctx.newPage();
    if(!fixture){
      emit('BROWSER_WINDOW_OPENED');
      ctx.on('page',()=>emit('BROWSER_WINDOW_OPENED'));
    }
    const url=checkedURL(fixture?q.fixtureURL:prefs.reportURL);
    await page.goto(url,{waitUntil:'domcontentloaded',timeout:60000});
    if(!process.argv.includes('--batch-session'))return await runTask(ctx,page,url);
    const input=require('node:readline').createInterface({input:process.stdin,crlfDelay:Infinity});
    const lines=input[Symbol.asyncIterator]();
    try{
      for(;;){
        try{await runTask(ctx,page,url);emit('BATCH_DONE');}
        catch(e){emit('BATCH_FAILED '+e.message.split('\n')[0]);}
        const next=await lines.next();if(next.done||next.value==='END_BATCH')break;
        const request=JSON.parse(next.value);
        q=JSON.parse(fs.readFileSync(request,'utf8'));out=path.dirname(request);
      }
    }finally{input.close();}
  }catch(e){
    emit('浏览器停在 '+await locations(ctx));await inspect(ctx,'skuInput');
    throw e;
  }finally{await ctx.close();}
}
async function collectTask(ctx,page){
    if(warehouses){
      await ready(ctx,page);await settings(ctx);
      const search=await find(ctx,'whSearch',f=>f.locator('#search_wms_co_id'),1000,true);
      if(search){
        await search.fill('');await search.press('Enter');await wait(800);
      }
      const names=search?await search.evaluate(el=>{
        for(let box=el.parentElement;box;box=box.parentElement){
          const inputs=[...box.querySelectorAll('input[type=checkbox]')].filter(input=>input.getClientRects().length);
          if(!inputs.length)continue;
          if(inputs.some(input=>!input.labels?.length && !input.parentElement.innerText.trim()))return [];
          return inputs.map(input=>(input.labels?.[0]?.innerText||input.parentElement.innerText).trim())
            .filter(name=>name && !/^(全选|全部|所有仓库|发货仓|组合商品拆分|实体编码拆分|排除发货前取消商品)$/.test(name));
        }
        return [];
      }):await (await find(ctx,'warehouse',f=>rowControl(f,'发货仓','select'))).locator('option').allTextContents();
      if(!names.length)throw Error('未能读取 ERP 仓库列表，可使用本地历史报表中的仓库');
      fs.writeFileSync(path.join(out,'scope.json'),JSON.stringify([...new Set(names.map(s=>s.trim()).filter(s=>s && !/^(全选|全部|所有仓库|请选择)$/.test(s)))]));return;
    }
    if(catalog){
      await ready(ctx,page);await settings(ctx);
      const codes=await pick(ctx,q.code,true);
      fs.writeFileSync(path.join(out,'scope.json'),JSON.stringify(codes));return;
    }
    for(const [name,key] of (audit?[['sales',q.code]]:[['sales',q.code],['reships','补发专用']])){
      current=name;
      emit(name==='sales'?'正在生成销售订单报表':'正在生成补发订单报表');
      await ready(ctx,page);
      const all=q.bulk && name==='sales';
      let skus;
      for(let attempt=0;attempt<3;attempt++){
        try{
          emit('已打开销售主题分析，正在设置日期和仓库');await settings(ctx);
          if(audit || all){
            const filter=await find(ctx,'bulkSkuFilter',f=>f.locator('input#sku_id.input_picker'));
            if((await filter.inputValue()).trim())throw Error('重置后仍有商品过滤条件，停止全商品采集');
            skus=['*'];break;
          }
          emit('正在选择商品 '+key);
          const reuse=name==='sales'?q.selectedSkus:(q.bulk?confirmedReshipCodes:null);
          skus=await pick(ctx,key,false,reuse);
          if(q.bulk && name==='reships')confirmedReshipCodes=skus;
          break;
        }catch(e){
          if(attempt===2 || !/not attached|detached|context.*destroyed|Cannot find context/i.test(e.message))throw e;
          emit('筛选框架已重新加载，重新核对查询条件');await wait(800);
        }
      }
      emit(all||audit?'正在生成完整订单商品报表':'已选择 '+skus.length+' 个商品，正在生成报表');
      const proof=await exportReport(ctx,name,skus);
      fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(proof));
    }
    if(audit){
      const java=path.join(__dirname,'..','runtime','bin','java.exe');
      const cli=(...args)=>require('node:child_process').execFileSync(java,['-Dfile.encoding=UTF-8','-cp','report-desk-desktop.jar;../lib/*','Desk',...args],{cwd:__dirname,encoding:'utf8'});
      const item=path.join(out,'sales.xlsx');
      cli('--verify-sales-export',out,'sales');
      const [a,b]=JSON.parse(cli('--find-pair',item));
      emit('选定同单只退一种的商品：'+a+' / '+b);
      current='auditOrders';
      for(const [name,targets] of [['a',[a]],['b',[b]],['ab',[a,b]]]){
        await settings(ctx);await pick(ctx,'',false,targets);const proof=await exportReport(ctx,name,targets);
        fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(proof));cli('--verify-sales-export',out,name);
      }
      cli('--verify-grain',a,b,item,...['a','b','ab'].map(n=>path.join(out,n+'.xlsx')));
      emit('A、B、A+B 三组逐单对账通过');
    }
}
main().catch(e=>{
  const hint=/launchPersistentContext|SingletonLock|ProcessSingleton/i.test(e.message)
    ? '；请关闭之前手动打开的售后专用浏览器窗口，再从「数据 → 恢复未完成的查询」重试。' : '';
  const trace=e.message.split('\n').filter(s=>/intercepts pointer|waiting for locator|not visible|not stable/.test(s)).slice(0,4);
  if(trace.length)emit(trace.join(' ').slice(0,950));
  emit('查询未发布：'+e.message.split('\n')[0]+hint);process.exitCode=1;
});
