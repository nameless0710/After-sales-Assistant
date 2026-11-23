import java.nio.file.*;
import java.util.*;

/** An ERP order-filter result certifies a group. Coverage extends only over its dates and warehouses. */
final class GroupReports {
    record Verified(Metrics.Result result,Set<String> aliases,String source){}

    static Path folder(String code)throws Exception{
        return Store.ROOT.resolve("verified-groups").resolve(Store.key(Metrics.norm(code)));
    }
    static Verified save(Metrics.Result result,String source)throws Exception{
        var entry=new Verified(result,Store.aliases(result.query()),source);
        Store.save(folder(result.query().code()).resolve(Store.key(result.query())+".json"),entry);
        return entry;
    }
    static Verified find(Metrics.Query q,String reportDay)throws Exception{
        var dir=folder(q.code());if(reportDay==null||!Files.isDirectory(dir))return null;
        var aliases=Store.aliases(q);Verified best=null;
        try(var files=Files.list(dir)){
            for(var f:files.filter(p->p.toString().endsWith(".json")).toList()){
                var entry=Store.read(f,Verified.class);var r=entry.result;var base=r.query();
                if(!reportDay.equals(r.reportDate())||!entry.aliases.equals(aliases)||!Metrics.norm(base.code()).equals(Metrics.norm(q.code())))continue;
                if(!Objects.equals(base.selectedSkus(),q.selectedSkus()))continue;
                if(!base.warehouse().equals(Metrics.ALL) &&
                    (q.warehouse().equals(Metrics.ALL)||!base.warehouses().containsAll(q.warehouses())))continue;
                if(q.start().compareTo(base.start())<0||q.end().compareTo(base.end())>0)continue;
                if(base.equals(q))return entry;
                if(best==null || r.collected().compareTo(best.result().collected())>0)best=entry;
            }
        }
        if(best==null)return null;
        var r=best.result;
        var filtered=new Metrics.Result(q,r.collected(),r.reportDate(),r.skus(),
            r.sales().stream().filter(row->Bulk.inRange(row,q)).toList(),
            r.replacements().stream().filter(row->Bulk.inRange(row,q)).toList());
        return new Verified(filtered,best.aliases,best.source);
    }
}
