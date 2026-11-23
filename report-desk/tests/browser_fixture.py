"""Exercise the real browser driver against nested frames and generated XLSX downloads."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from threading import Thread
from urllib.parse import urlsplit
from xml.sax.saxutils import escape
from zipfile import ZipFile
import json
import hashlib
import os
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[1]
CLASSPATH = os.environ.get('DESK_TEST_CLASSPATH','report-desk-desktop.jar;../lib/*')
SHELL = '<iframe src="/filters" style="width:100%;height:900px"></iframe>'
FILTERS = """
<button onclick="location.reload()">重置查询</button><button>明细(订单)</button>
<span>发货时间</span><input type=date><input type=date>
<label><input type=checkbox>组合商品拆分</label><label><input type=checkbox>实体编码拆分</label>
<label><input type=checkbox>排除发货前取消商品</label>
<div><span>包含商品</span><button onclick="picker.hidden=false">…</button></div>
<section hidden id=picker><input placeholder="商品编码" id=term><span>启用</span>
<button onclick="search()">搜索</button><b id=tot></b><b id=chosen></b>
<table><thead><tr><th><input type=checkbox id=all onchange="choosePage()"></th><th>商品编码</th></tr></thead><tbody id=items></tbody></table>
<span class="ant-select-selection-item">2</span><button aria-label="下一页" id=nextPage onclick="later()">下一页</button>
<button onclick="picker.hidden=true">确定</button></section>
<button onclick="generate()">生成报表</button><span>数据更新至：2026-11-19</span>
<iframe style="display:none" data-url="detail_order.aspx" src="/detail_order.aspx?stale"></iframe>
<iframe id=actual data-url="detail_order.aspx" style="width:99%;height:320px" src="/detail_order.aspx?stale"></iframe>
<script>
let codes=[],page=0,selected=new Set();
function render(){items.innerHTML=codes.slice(page*2,page*2+2).map(c=>'<tr><td></td><td>'+c+'</td></tr>').join('');
all.checked=false;nextPage.disabled=(page+1)*2>=codes.length;}
function later(){nextPage.disabled=true;setTimeout(()=>{page++;render()},1100);}
function choosePage(){codes.slice(page*2,page*2+2).forEach(c=>selected.add(c));chosen.textContent='已选商品('+selected.size+')';}
function search(){codes=term.value==='补发专用'?['补发专用','补发专用-广东仓']:['品-A','品-B','品-C'];page=0;selected.clear();render();
tot.textContent='共'+codes.length+'条';chosen.textContent='已选商品(0)'}
function generate(){const dates=[...document.querySelectorAll('[type=date]')].map(x=>x.value);
if(dates[0]!=='2026-08-01'||!['2026-11-17','2026-11-18'].includes(dates[1])||!all.checked||!document.querySelector('label input').checked)return;
actual.src='/detail_order.aspx?'+(term.value==='补发专用'?'reships':dates[1]==='2026-11-17'?'empty':'sales');}
</script>"""

def workbook(rows):
    out = BytesIO()
    def cell(i, n, text):
        return f'<c r="{chr(65+i)}{n}" t="inlineStr"><is><t>{escape(str(text))}</t></is></c>'
    sheet = ''.join(f'<row r="{n}">'+''.join(cell(i,n,s) for i,s in enumerate(row))+'</row>' for n,row in enumerate(rows,1))
    with ZipFile(out,'w') as z:
        z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+sheet+'</sheetData></worksheet>')
    return out.getvalue()

class Server(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        u=urlsplit(self.path)
        if u.path.endswith('.xlsx'):
            rows=[['内部订单号','发货日期','发货仓','实发数量','退货数量','卖家备注']]
            for oid,sent,ret,note in ([('123',2,1,''),('123',1,0,''),('124',1,0,'')] if u.path=='/sales.xlsx'
                else [('431',1,0,'补发品-A'),('431',1,0,'补发品-B'),('432',1,0,'其他')]):
                rows.append([oid,'2026/10/1','仓',sent,ret,note])
            body=workbook(rows);self.send_response(200);self.send_header('Content-Disposition','attachment; filename='+u.path[1:])
        else:
            if u.path=='/filters': html=FILTERS
            elif u.path=='/detail_order.aspx' and u.query=='empty':html='<span>没有符合条件的数据</span>'
            elif u.path=='/detail_order.aspx':
                shipped,returned=(4,1) if u.query=='sales' else (99,98)
                html=f'<table><thead><tr><th>订单</th><th>实发数量</th><th>退货数量</th></tr></thead><tbody><tr><td>##</td><td>{shipped}</td><td>{returned}</td></tr></tbody></table><span>共 3 条记录</span><a download href="/{u.query}.xlsx">导出EXCEL</a>'
            else: html=SHELL
            body=('<!doctype html><meta charset="utf-8">'+html).encode();self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8')
        self.end_headers();self.wfile.write(body)

class TestWorkflow(unittest.TestCase):
    def test_published_reconciliation(self):
        q=dict(code='品-A',start='2026-08-01',end='2026-08-31',warehouse='所有仓库')
        def line(order,sku,sent,ret,note=''):
            return dict(order=order,date='2026-08-12',warehouse='仓',sku=sku,shipped=sent,returned=ret,keyword=note)
        with tempfile.TemporaryDirectory() as temp:
            store=Path(temp);(store/'results').mkdir()
            def compact(obj): return json.dumps(obj,ensure_ascii=False,separators=(',',':')).encode()
            cache=store/'results'/(hashlib.sha256(compact(q)).hexdigest()+'.json')
            meta=dict(start=q['start'],end=q['end'],reportDate='2026-11-20',collected='2026-11-21T02:00:00Z',parts=['2026-08-01_2026-08-31.json'])
            ref=dict(query=q,reportDate=meta['reportDate'],collected=meta['collected'],skus=['品-A'],
                     sales=[line('321','',4,1)],replacements=[line('410','',0,0,'品-A')])
            cache.write_bytes(compact(ref))
            def archive(oid):
                batch={**ref,'sales':[line(oid,'品-A',3,0),line(oid,'品-A',1,1),line('321','品-B',2,0)],
                       'replacements':[line('410','补发专用',0,0,'补发品-A'),line('411','补发专用',0,0,'补发品-B')]}
                with ZipFile(store/'bulk.zip','w') as z:
                    z.writestr('index.json',compact(meta));z.writestr(meta['parts'][0],compact(batch))
            args=[APP.parent/'runtime/bin/java.exe','-Dfile.encoding=UTF-8','-cp',CLASSPATH,'Desk','--verify-published',*q.values()]
            def verify():return subprocess.run(args,cwd=APP,env={**os.environ,'AFTERSALES_STORE':temp},capture_output=True,encoding='utf8')
            archive('321');ok=verify();self.assertEqual(ok.returncode,0,ok.stderr)
            archive('999');self.assertNotEqual(verify().returncode,0)
            archive('321');ref['reportDate']='2026-11-19';cache.write_bytes(compact(ref))
            self.assertNotEqual(verify().returncode,0)
    def test_grain_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp);env={**os.environ,'AFTERSALES_STORE':str(p/'store')}
            header=['内部订单号','商品编码','实发数量','退货数量']
            a=['123','A',2,1];b=['123','B',1,0]
            for name,rows in [('items',[a,b]),('a',[a]),('b',[b]),('ab',[['123','',3,1]])]:
                (p/(name+'.xlsx')).write_bytes(workbook([header,*rows]))
            args=[APP.parent/'runtime/bin/java.exe','-cp',CLASSPATH,'Desk','--verify-grain','A','B',*[p/(n+'.xlsx') for n in ['items','a','b','ab']]]
            def run():return subprocess.run(args,cwd=APP,env=env,capture_output=True)
            (p/'b.xlsx').write_bytes(workbook([header,['123','B',1,1]]))
            self.assertNotEqual(run().returncode,0);self.assertFalse((p/'store/grain-verified.json').exists())
            (p/'b.xlsx').write_bytes(workbook([header,b]))
            self.assertEqual(run().returncode,0);self.assertTrue((p/'store/grain-verified.json').exists())
    def test_pipeline(self):
        server=ThreadingHTTPServer(('127.0.0.1',0),Server)
        thread=Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                p=Path(tmp);q=dict(code='品',start='2026-08-01',end='2026-11-18',warehouse='所有仓库',fixtureURL=f'http://127.0.0.1:{server.server_port}/')
                (p/'query.json').write_text(json.dumps(q),encoding='utf8')
                other=p/'second';other.mkdir();next_request=other/'query.json';next_request.write_text(json.dumps({**q,'end':'2026-11-17'}),encoding='utf8')
                job=subprocess.run([APP/'driver/node.exe',APP/'browser.cjs',p/'query.json','--fixture','--batch-session'],
                    input=json.dumps(str(next_request))+'\nEND_BATCH\n',text=True,encoding='utf8',capture_output=True,timeout=180)
                self.assertEqual(job.returncode,0,job.stderr+job.stdout)
                self.assertEqual(job.stdout.count('BATCH_DONE'),2,job.stdout)
                self.assertTrue((other/'reships.xlsx').is_file())
                self.assertFalse((other/'sales.xlsx').exists())
                self.assertEqual(json.loads((other/'sales.json').read_text('utf8'))['count'],0)
                self.assertEqual(json.loads((p/'sales.json').read_text('utf8'))['shipped'],4)
                self.assertEqual(json.loads((p/'sales.json').read_text('utf8'))['skus'],['品-A','品-B','品-C'])
                java=APP.parent/'runtime/bin/java.exe'
                args=[java,'-Dfile.encoding=UTF-8','-cp',CLASSPATH,'Desk','--verify-batch',str(p)]
                def verify():return subprocess.run(args,cwd=APP,capture_output=True,encoding='utf8')
                good=verify();self.assertEqual(good.returncode,0,good.stderr);self.assertIn('100.00%',good.stdout)
                empty=subprocess.run([*args[:-1],str(other)],cwd=APP,capture_output=True,encoding='utf8')
                self.assertEqual(empty.returncode,0,empty.stderr);self.assertIn('售后率 —',empty.stdout)
                proof=json.loads((p/'sales.json').read_text('utf8'));proof['count']=10
                (p/'sales.json').write_text(json.dumps(proof),encoding='utf8')
                self.assertNotEqual(verify().returncode,0)
        finally:server.shutdown();server.server_close()

if __name__=='__main__':unittest.main()
