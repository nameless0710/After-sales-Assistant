import java.nio.file.*;
import java.util.*;

/** A, B and A+B are independently filtered order exports for the same dates and warehouse. */
final class Grain {
    record Qty(long shipped,long returned){Qty plus(Qty v){return new Qty(shipped+v.shipped,returned+v.returned);}}
    static List<String> findPair(Path file)throws Exception{
        var orders=new LinkedHashMap<String,Map<String,Qty>>();
        Xlsx.read(file,Set.of("内部订单号","商品编码","实发数量","退货数量"),r->{
            String id=r.getOrDefault("内部订单号",""),sku=r.getOrDefault("商品编码","");
            if(id.matches("[0-9]+")&&!sku.isBlank())orders.computeIfAbsent(id,k->new TreeMap<>())
                .merge(sku,new Qty(Metrics.qty(r.get("实发数量")),Metrics.qty(r.get("退货数量"))),Qty::plus);
        });
        for(var group:orders.values()){
            String a=null,b=null;
            for(var entry:group.entrySet())if(entry.getValue().shipped>0){
                if(entry.getValue().returned>0)a=entry.getKey();else if(entry.getValue().returned==0)b=entry.getKey();
            }
            if(a!=null&&b!=null && !a.contains("补发专用") && !b.contains("补发专用"))return List.of(a,b);
        }
        throw new IllegalArgumentException("当日完整明细中未找到同时发出两个商品且只退一种的订单");
    }
    static Map<String,Qty> orders(Path file,String sku)throws Exception{
        var map=new TreeMap<String,Qty>();var required=new HashSet<>(Set.of("内部订单号","实发数量","退货数量"));
        if(sku!=null)required.add("商品编码");
        Xlsx.read(file,required,r->{
            String id=r.getOrDefault("内部订单号","");
            if(id.matches("##|总计|合计"))return;
            if(!id.matches("[0-9]+"))throw new IllegalArgumentException("对账报表缺失内部订单号");
            if(sku==null || sku.equalsIgnoreCase(r.get("商品编码")))map.merge(id,new Qty(Metrics.qty(r.get("实发数量")),Metrics.qty(r.get("退货数量"))),Qty::plus);
        });return map;
    }
    static void verify(String a,String b,Path item,Path orderA,Path orderB,Path orderBoth)throws Exception{
        if(a.isBlank()||b.isBlank()||a.equalsIgnoreCase(b))throw new IllegalArgumentException("请选择两个不同的完整 SKU");
        var left=orders(item,a);var right=orders(item,b);
        boolean mixed=left.entrySet().stream().anyMatch(e->{
            var r=right.get(e.getKey());var l=e.getValue();
            return r!=null && l.shipped>0 && r.shipped>0 && ((l.returned>0 && r.returned==0)||(l.returned==0 && r.returned>0));
        });
        if(!mixed)throw new IllegalArgumentException("样本缺少同时发出 A 和 B、只退一种的订单");
        var both=new TreeMap<>(left);right.forEach((k,v)->both.merge(k,v,Qty::plus));
        if(!left.equals(orders(orderA,null)) || !right.equals(orders(orderB,null)) || !both.equals(orders(orderBoth,null)))
            throw new IllegalArgumentException("A、B 或 A+B 的逐单实发/退货与订单报表不一致");
        Store.save(Store.ROOT.resolve("grain-verified.json"),Map.of("skuA",a,"skuB",b,"verified",java.time.Instant.now().toString(),"orders",both.size()));
    }
}
