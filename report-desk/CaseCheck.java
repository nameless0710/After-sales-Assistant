import com.google.gson.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Offline reconciliation, separate from production snapshots; never sends captured requests. */
final class CaseCheck {
    record Evidence(String summary,List<Metrics.Line> hits){}
    static Evidence run(Path folder)throws Exception{
        String code="龙竹-50-60cm";
        String picker=Files.readString(folder.resolve("商品选择器请求 案例一.txt"));
        List<String> skus=new ArrayList<>();int announced=-1;
        for(String block:picker.split("响应：")){
            String text=block.stripLeading();if(!text.startsWith("{"))continue;
            var parser=new JsonStreamParser(text);
            var obj=parser.next().getAsJsonObject();
            for(var row:obj.getAsJsonArray("data"))skus.add(row.getAsJsonObject().get("sku_id").getAsString());
            int n=obj.getAsJsonObject("page").get("count").getAsInt();if(n>=0)announced=n;
        }
        if(skus.size()!=announced)throw new IllegalArgumentException("商品抓包数量不完整");
        String sales=Files.readString(folder.resolve("销售订单明细请求 案例一.txt"));
        int response=sales.indexOf("0|{");if(response<0)throw new IllegalArgumentException("缺少报表响应");
        var filterText=URLDecoder.decode(sales.substring(0,response).replace("+","%2B"),StandardCharsets.UTF_8).replace("\\\"","\"");
        var f=Pattern.compile("\"C\\.sku_id\",\"v\":\"([^\"]+)\"").matcher(filterText);
        if(!f.find())throw new IllegalArgumentException("无法识别报表商品条件");
        var expected=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);expected.addAll(Arrays.asList(f.group(1).split(",")));
        if(expected.size()!=skus.size()||!expected.containsAll(skus))throw new IllegalArgumentException("报表与商品选择器清单不一致");
        var outer=JsonParser.parseString(sales.substring(response+2).strip()).getAsJsonObject();
        var body=JsonParser.parseString(outer.get("ReturnValue").getAsString()).getAsJsonObject();
        var rows=body.getAsJsonArray("datas");
        var sum=rows.get(rows.size()-1).getAsJsonObject();
        int full=body.getAsJsonObject("dp").get("DataCount").getAsInt();
        if(sum.get("___count").getAsInt()!=full)throw new IllegalArgumentException("汇总计数与分页不一致");
        Path workbook=folder.resolve("销售主题分析_明细订单_20261120124101_210934984_1.xlsx");
        var hits=new LinkedHashMap<String,Metrics.Line>();var aliases=Store.aliases(code);
        Xlsx.read(workbook,Set.of("内部订单号","发货日期","发货仓","卖家备注"),r->{
            String keyword=Metrics.match(r.getOrDefault("卖家备注",""),skus,aliases);
            if(!keyword.isEmpty()){
                String id=r.get("内部订单号");
                hits.put(id,new Metrics.Line(id,Metrics.day(r.get("发货日期")),r.get("发货仓"),"",0,0,keyword));
            }
        });
        String msg=String.format("匹配 %d 项 · 实发 %d 件 · 退货 %d 件 · 补发 %d 单 · 销售明细 %d/%d 行，售后率待完整明细核算",
            skus.size(),sum.get("sent_qty").getAsLong(),sum.get("退货数量").getAsLong(),hits.size(),rows.size()-1,full);
        return new Evidence(msg,List.copyOf(hits.values()));
    }
}
