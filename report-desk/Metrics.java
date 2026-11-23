import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

final class Metrics {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final String ALL="所有仓库";
    record Query(String code,String start,String end,String warehouse,List<String> selectedSkus) {
        Query(String code,String start,String end,String warehouse) {this(code,start,end,warehouse,null);}
        Query {
            code=code.strip(); warehouse=warehouse.strip();
            if(selectedSkus!=null){
                var keyword=norm(code);
                var unique=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
                selectedSkus.forEach(s->unique.add(s.strip()));
                if(unique.isEmpty() || unique.stream().anyMatch(s->s.isEmpty() || !norm(s).contains(keyword)))
                    throw new IllegalArgumentException("请选择匹配的商品");
                selectedSkus=List.copyOf(unique);
            }
            if(code.isEmpty() || warehouse.isEmpty() || LocalDate.parse(start).isAfter(LocalDate.parse(end)))
                throw new IllegalArgumentException("请检查商品编码、日期和仓库");
        }
        List<String> warehouses(){return warehouse.equals(ALL)?List.of():List.of(warehouse.split("\n"));}
        boolean includesWarehouse(String name){return warehouse.equals(ALL)||warehouses().contains(name);}
        String warehouseLabel(){return warehouse.replace("\n","、");}
        static Query rolling(String code,String warehouse) {
            var today=LocalDate.now(ZONE);
            return new Query(code,today.minusMonths(3).withDayOfMonth(1).toString(),today.toString(),warehouse);
        }
    }
    record Proof(int count,Long shipped,Long returned,List<String> skus,String reportDate) {}
    record Line(String order,String date,String warehouse,String sku,long shipped,long returned,String keyword) {}
    record Result(Query query,String collected,String reportDate,List<String> skus,List<Line> sales,List<Line> replacements) {
        long shipped(){return sales.stream().mapToLong(Line::shipped).sum();}
        long returned(){return sales.stream().mapToLong(Line::returned).sum();}
        long positive(boolean shipping){
            Map<String,Long> qty=new HashMap<>();
            for(var row:sales) qty.merge(row.order,shipping?row.shipped:row.returned,Long::sum);
            return qty.values().stream().filter(v->v>0).count();
        }
        long reships(){return replacements.stream().map(Line::order).distinct().count();}
        String rate(){return positive(true)==0?"—":String.format(Locale.ROOT,"%.2f%%",100.0*(positive(false)+reships())/positive(true));}
    }
    static String norm(String s){return s.strip().toLowerCase(Locale.ROOT);}
    static long qty(String s){return s==null||s.isBlank()?0:new BigDecimal(s.replace(",","")).longValueExact();}
    static String day(String raw){
        if(raw.matches("[0-9]+(\\.[0-9]+)?")) return LocalDate.of(1899,12,30).plusDays(new BigDecimal(raw).longValue()).toString();
        var m=Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:$|[T ].*)").matcher(raw);
        if(!m.matches()) throw new IllegalArgumentException("无法读取发货日期 "+raw);
        return LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))).toString();
    }
    static String match(String remark,List<String> skus,Set<String> aliases){
        String text=norm(remark);
        for(String sku:skus){
            int from=0; String key=norm(sku);
            while((from=text.indexOf(key,from))>=0){
                from+=key.length();
                if(from==text.length() || !String.valueOf(text.charAt(from)).matches("[a-z0-9_\\-]")) return sku;
            }
        }
        for(String a:aliases) if(!a.isBlank() && text.contains(norm(a))) return a;
        return "";
    }
    static List<Line> read(Query q,Path file,Proof proof,boolean replacement,List<String> selected,Set<String> aliases) throws Exception {
        return read(q,file,proof,replacement,selected,aliases,false);
    }
    static List<Line> read(Query q,Path file,Proof proof,boolean replacement,List<String> selected,Set<String> aliases,boolean bulk) throws Exception {
        Set<String> required=new HashSet<>(List.of("内部订单号","发货日期","发货仓"));
        if(bulk && !replacement)required.add("商品编码");
        if(replacement) required.add("卖家备注"); else required.addAll(List.of("实发数量","退货数量"));
        if(proof.count<0 || proof.skus==null || proof.skus.isEmpty()) throw new IllegalArgumentException("缺少网页商品清单或报表行数");
        if(!replacement && (proof.shipped==null || proof.returned==null)) throw new IllegalArgumentException("缺少网页全表实发或退货合计");
        if(replacement && proof.skus.stream().anyMatch(s->!s.contains("补发专用"))) throw new IllegalArgumentException("补发报表筛选混入其他商品");
        if(proof.count==0 && !Files.exists(file)) {
            if(!replacement && (proof.shipped!=0 || proof.returned!=0)) throw new IllegalArgumentException("零行报表合计异常");
            return List.of();
        }
        var rows=new ArrayList<Line>(); int[] count={0}; long[] totals={0,0};
        var longestFirst=selected.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        Xlsx.read(file,required,row->{
            String id=row.getOrDefault("内部订单号","");
            if(id.matches("合计|总计|##")) return;
            if(!id.matches("[0-9]+")) throw new IllegalArgumentException("导出中有缺失或无效的内部订单号");
            String date=day(row.getOrDefault("发货日期","")), wh=row.getOrDefault("发货仓","");
            if(date.compareTo(q.start)<0 || date.compareTo(q.end)>0 || !q.includesWarehouse(wh))
                throw new IllegalArgumentException("导出包含超出查询日期或仓库的订单");
            count[0]++;
            long shipped=qty(row.get("实发数量")),returned=qty(row.get("退货数量"));
            totals[0]+=shipped;totals[1]+=returned;
            String hit=replacement?(bulk?row.getOrDefault("卖家备注",""):match(row.getOrDefault("卖家备注",""),longestFirst,aliases)):"";
            if(replacement && hit.isEmpty()) return;
            String sku=row.getOrDefault("商品编码",row.getOrDefault("订单商品明细",""));
            if(bulk && !replacement && sku.isEmpty())throw new IllegalArgumentException("订单商品明细存在缺少商品编码的行");
            if(sku.isEmpty() && proof.skus.size()==1) sku=proof.skus.get(0);
            rows.add(new Line(id,date,wh,sku,shipped,returned,hit));
        });
        if(count[0]!=proof.count) throw new IllegalArgumentException("网页共 "+proof.count+" 条，文件有 "+count[0]+" 条；请导出全部页");
        if(!replacement && (totals[0]!=proof.shipped || totals[1]!=proof.returned))
            throw new IllegalArgumentException("导出明细合计与网页汇总不符");
        return rows;
    }
    static Result build(Query q,Path dir,Proof sale,Proof resend,Set<String> aliases) throws Exception {
        if(sale.skus.stream().anyMatch(s->s.isBlank() || !norm(s).contains(norm(q.code)))) throw new IllegalArgumentException("网页商品列表与查询编码不符");
        if(q.selectedSkus!=null && (q.selectedSkus.size()!=sale.skus.size() ||
            !new HashSet<>(q.selectedSkus.stream().map(Metrics::norm).toList()).equals(new HashSet<>(sale.skus.stream().map(Metrics::norm).toList()))))
            throw new IllegalArgumentException("网页商品清单与勾选范围不符");
        if(!Objects.equals(sale.reportDate,resend.reportDate)) throw new IllegalArgumentException("两份报表的数据更新时间不同，请重新生成");
        var sales=read(q,dir.resolve("sales.xlsx"),sale,false,sale.skus,aliases);
        var replacements=read(q,dir.resolve("reships.xlsx"),resend,true,sale.skus,aliases);
        return new Result(q,Instant.now().toString(),sale.reportDate,sale.skus,sales,replacements);
    }
}
