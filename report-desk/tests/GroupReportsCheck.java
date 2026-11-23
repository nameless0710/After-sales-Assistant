import java.time.Instant;
import java.util.*;

public class GroupReportsCheck {
    static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception {
        var query=new Metrics.Query("品-A","2026-08-01","2026-08-31",Metrics.ALL);
        var sales=List.of(new Metrics.Line("1","2026-08-03","仓A","",2,1,""),
            new Metrics.Line("2","2026-08-19","仓B","",1,0,""));
        var hits=List.of(new Metrics.Line("3","2026-08-19","仓B","",0,0,"品-A"));
        var expected=new Metrics.Result(query,Instant.now().toString(),"2026-11-20",List.of("品-A"),sales,hits);
        GroupReports.save(expected,"网页订单报表");
        require(GroupReports.find(query,"2026-11-20").result().returned()==1,"网页替代结果未命中");
        require(GroupReports.find(query,"2026-11-21")==null,"错误复用了旧数据日");
        require(GroupReports.find(new Metrics.Query("品","2026-08-01","2026-08-31",Metrics.ALL),"2026-11-20")==null,"错误扩大商品范围");
        var subset=new Metrics.Query("品-A","2026-08-10","2026-08-31","仓B");
        var selected=GroupReports.find(subset,"2026-11-20").result();
        require(selected.sales().size()==1 && selected.shipped()==1 && selected.returned()==0 && selected.reships()==1,"日期和仓库筛选不正确");
        var moved=new Metrics.Result(query,expected.collected(),expected.reportDate(),expected.skus(),
            List.of(new Metrics.Line("1","2026-08-03","仓B","",2,1,""),sales.get(1)),hits);
        require(!Bulk.equivalent(expected,moved),"逐单核验忽略了仓库归属");
        GroupReports.save(moved,"商品明细与订单报表存在差异，已采用网页订单报表");
        require(GroupReports.find(query,"2026-11-20").source().contains("存在差异"),"未发布报表回退来源");
        var multi=new Metrics.Query("品-A","2026-08-01","2026-08-31","仓A\n仓B",List.of("品-A"));
        var one=new Metrics.Query("品-A",multi.start(),multi.end(),"仓A",multi.selectedSkus());
        var two=new Metrics.Query("品-A",multi.start(),multi.end(),"仓B",multi.selectedSkus());
        var first=new Metrics.Result(one,expected.collected(),expected.reportDate(),expected.skus(),List.of(sales.get(0)),hits);
        var second=new Metrics.Result(two,expected.collected(),expected.reportDate(),expected.skus(),
            List.of(new Metrics.Line("1","2026-08-03","仓B","品-A",3,0,"")),hits);
        var joined=Store.combine(multi,List.of(first,second));
        require(joined.shipped()==5 && joined.positive(true)==1 && joined.reships()==1,"多仓合并重复计算了订单");
        require(GroupReports.find(multi,"2026-11-20")==null,"默认全选缓存被错误复用于手动商品选择");
        GroupReports.save(joined,"网页订单报表");
        require(GroupReports.find(one,"2026-11-20").result().shipped()==2,"多仓缓存未正确过滤发货仓");
        require(GroupReports.find(new Metrics.Query("品-A",multi.start(),multi.end(),"仓C",multi.selectedSkus()),"2026-11-20")==null,
            "多仓缓存被错误复用于未选发货仓");
        System.out.println("商品组缓存、日期仓库覆盖及归属核验通过");
    }
}
