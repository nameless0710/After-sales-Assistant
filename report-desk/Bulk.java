import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

final class Bulk {
    record Index(String start,String end,String collected,String reportDate,List<String> parts){}
    static Path snapshot(){return Store.ROOT.resolve("bulk.zip");}
    record Range(LocalDate first,LocalDate last){}
    static ArrayDeque<Range> ranges(Metrics.Query q,Path staging)throws IOException{
        var first=LocalDate.parse(q.start());var last=LocalDate.parse(q.end());
        var cut=new TreeSet<LocalDate>();cut.add(last);
        for(var month=YearMonth.from(first);!month.isAfter(YearMonth.from(last));month=month.plusMonths(1))
            if(month.atEndOfMonth().isBefore(last))cut.add(month.atEndOfMonth());
        if(Files.exists(snapshot()))try(var zip=new ZipFile(snapshot().toFile())){
            var old=read(zip,"index.json",Index.class);
            for(String part:old.parts)if(part.matches("\\d{4}-\\d{2}-\\d{2}_\\d{4}-\\d{2}-\\d{2}\\.json")){
                var day=LocalDate.parse(part.substring(11,21));
                if(!day.toString().equals(old.end)&&!day.isBefore(first)&&day.isBefore(last))cut.add(day);
            }
        }
        try(var files=Files.list(staging)){for(var p:files.toList()){
            var name=p.getFileName().toString();
            if(!name.matches("\\d{4}-\\d{2}-\\d{2}_\\d{4}-\\d{2}-\\d{2}\\.json"))continue;
            for(var day:List.of(LocalDate.parse(name.substring(0,10)).minusDays(1),LocalDate.parse(name.substring(11,21))))
                if(!day.isBefore(first)&&day.isBefore(last))cut.add(day);
        }}
        var ranges=new ArrayDeque<Range>();var start=first;
        for(var stop:cut){ranges.add(new Range(start,stop));start=stop.plusDays(1);}
        return ranges;
    }
    static boolean enabled(){return Files.isRegularFile(Store.ROOT.resolve("grain-verified.json"));}
    static void put(ZipOutputStream zip,String key,Object obj)throws IOException{
        zip.putNextEntry(new ZipEntry(key));var writer=new OutputStreamWriter(zip,StandardCharsets.UTF_8);
        Store.JSON.toJson(obj,writer);writer.flush();zip.closeEntry();
    }
    static <T>T read(ZipFile zip,String name,Class<T> type)throws IOException{
        var entry=zip.getEntry(name);if(entry==null)throw new IOException("共享批次不完整");
        try(var r=new InputStreamReader(zip.getInputStream(entry),StandardCharsets.UTF_8)){return Store.JSON.fromJson(r,type);}
    }
    static void collect(Consumer<String> progress)throws Exception{
        if(!enabled())throw new IllegalStateException("请先完成 A/B 逐单对账");
        Files.createDirectories(Store.LOCAL);
        try(var channel=java.nio.channels.FileChannel.open(Store.ROOT.resolve("collector.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("采集机正在执行其他查询");
            var q=Metrics.Query.rolling("*",Metrics.ALL);
            var tmp=Files.createTempFile(Store.ROOT,".bulk-",".tmp");
            var parts=new ArrayList<String>();String reportDay=null;
            var staging=Store.LOCAL.resolve("verified-parts").resolve(Store.key(Store.ROOT.toAbsolutePath().normalize().toString())).resolve(q.end());
            Files.createDirectories(staging);var used=new ArrayList<Path>();
            try{
                var queue=ranges(q,staging);
                try(var zip=new ZipOutputStream(Files.newOutputStream(tmp));var browser=new BatchBrowser()){
                    while(!queue.isEmpty()){
                        var range=queue.remove();var start=range.first;var stop=range.last;
                        progress.accept("刷新全部商品 "+start+" 至 "+stop);
                        String name=start+"_"+stop+".json";var staged=staging.resolve(name);
                        Metrics.Result slice;
                        try {
                            if(Files.exists(staged)){slice=Store.read(staged,Metrics.Result.class);progress.accept("复用当天已核验的分段");}
                            else{
                                slice=Store.fetch(new Metrics.Query("*",start.toString(),stop.toString(),Metrics.ALL),progress,true,browser);
                                Store.save(staged,slice);
                            }
                        }catch(Exception e){
                            String msg=String.valueOf(e.getMessage());
                            if(start.equals(stop)||!(msg.contains("Excel 下载")||msg.contains("导出全部页")||msg.contains("单批行数")||msg.contains("导出文件格式")))throw e;
                            var middle=start.plusDays((stop.toEpochDay()-start.toEpochDay())/2);
                            queue.addFirst(new Range(middle.plusDays(1),stop));queue.addFirst(new Range(start,middle));
                            progress.accept("单次导出未完成，改为较短的发货区间");continue;
                        }
                        if(!parts.isEmpty() && !Objects.equals(reportDay,slice.reportDate()))throw new IOException("刷新期间报表数据更新时间发生变化");
                        reportDay=slice.reportDate();
                        put(zip,name,slice);parts.add(name);used.add(staged);
                    }
                    put(zip,"index.json",new Index(q.start(),q.end(),Instant.now().toString(),reportDay,parts));
                }
                reconcile(tmp,reportDay);
                Files.move(tmp,snapshot(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                for(var staged:used)Files.deleteIfExists(staged);
            }finally{Files.deleteIfExists(tmp);}
        }
    }
    static GroupReports.Verified query(Metrics.Query q)throws Exception{
        if(!enabled()||!Files.isRegularFile(snapshot()))return null;
        Index meta;
        try(var zip=new ZipFile(snapshot().toFile())){
            meta=read(zip,"index.json",Index.class);
            if(q.start().compareTo(meta.start)<0 || q.end().compareTo(meta.end)>0)return null;
        }
        var verified=GroupReports.find(q,meta.reportDate);
        if(verified!=null)return verified;
        return verifyGroup(q,false);
    }
    static Metrics.Result select(ZipFile zip,Index meta,Metrics.Query q,List<String> codes)throws Exception{
            if(codes.isEmpty())throw new IllegalArgumentException("没有匹配的启用商品");
            var keys=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);keys.addAll(codes);
            var longest=codes.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
            var aliases=Store.aliases(q);var sales=new ArrayList<Metrics.Line>();var hits=new ArrayList<Metrics.Line>();
            for(var part:meta.parts){
                var month=YearMonth.from(LocalDate.parse(part.substring(0,10)));
                if(month.atEndOfMonth().toString().compareTo(q.start())<0 || month.atDay(1).toString().compareTo(q.end())>0)continue;
                var slice=read(zip,part,Metrics.Result.class);
                for(var row:slice.sales())if(inRange(row,q)&&keys.contains(row.sku()))sales.add(row);
                for(var row:slice.replacements())if(inRange(row,q)){
                    String hit=Metrics.match(row.keyword(),longest,aliases);
                    if(!hit.isEmpty())hits.add(new Metrics.Line(row.order(),row.date(),row.warehouse(),row.sku(),0,0,hit));
                }
            }
            return new Metrics.Result(q,meta.collected,meta.reportDate,codes,sales,hits);
    }
    static void reconcile(Path file,String reportDay)throws Exception{
        var refs=Store.ROOT.resolve("results");if(reportDay==null||!Files.isDirectory(refs))return;
        try(var zip=new ZipFile(file.toFile());var list=Files.list(refs)){
            var meta=read(zip,"index.json",Index.class);
            Metrics.Result baseline=null;int checked=0;
            // Prefer a small, populated reference across all warehouses for the live check.
            var rank=Comparator.comparing((Metrics.Result r)->r.sales().isEmpty())
                .thenComparing(r->!r.query().warehouse().equals(Metrics.ALL))
                .thenComparingInt(r->r.skus().size()).thenComparing(r->r.query().end());
            for(var p:list.filter(n->n.toString().endsWith(".json")).sorted().toList()){
                var reference=Store.read(p,Metrics.Result.class);var q=reference.query();
                if(q.start().compareTo(meta.start)<0||q.end().compareTo(meta.end)>0)continue;
                if(baseline==null || rank.compare(reference,baseline)<0)baseline=reference;
                if(!reportDay.equals(reference.reportDate()))continue;
                certify(zip,meta,reference);checked++;
            }
            if(checked==0 && baseline!=null){
                System.out.println("历史参考报表的数据日较早，重新生成一份独立的逐单报表对账");
                var reference=Store.fetch(baseline.query(),System.out::println);
                if(!reportDay.equals(reference.reportDate()))throw new IOException("对账期间报表更新时间变化，稍后重新刷新");
                var verified=certify(zip,meta,reference);
                System.out.printf("%s · %s，实发 %d 件、退货 %d 件、补发 %d 单，售后率 %s%n",
                    verified.source(),reference.query().code(),reference.shipped(),reference.returned(),reference.reships(),reference.rate());
            }
        }
    }
    static void compare(ZipFile zip,Index meta,Metrics.Result reference)throws Exception{
        var actual=select(zip,meta,reference.query(),reference.skus());
        if(!equivalent(reference,actual))
            throw new IOException("集中批次与已核验的逐单报表不一致："+reference.query().code()+"，继续保留按商品报表");
    }
    static GroupReports.Verified certify(ZipFile zip,Index meta,Metrics.Result reference)throws Exception{
        var actual=select(zip,meta,reference.query(),reference.skus());
        if(equivalent(reference,actual))return GroupReports.save(actual,"集中商品明细与网页逐单一致");
        System.out.printf("商品明细汇总实发 %d、退货 %d、补发 %d；网页订单汇总实发 %d、退货 %d、补发 %d%n",
            actual.shipped(),actual.returned(),actual.reships(),reference.shipped(),reference.returned(),reference.reships());
        var expected=byOrder(reference.sales());var found=byOrder(actual.sales());
        var keys=new HashSet<>(expected.keySet());keys.addAll(found.keySet());int printed=0;
        for(var key:keys)if(!Objects.equals(expected.get(key),found.get(key)) && printed++<5)
            System.out.printf("差异订单 %s，发货日 %s，仓库 %s：网页 %s，商品明细 %s%n",key.order,key.date,key.warehouse,expected.get(key),found.get(key));
        return GroupReports.save(reference,"商品明细与订单报表存在差异，已采用网页订单报表");
    }
    static boolean equivalent(Metrics.Result reference,Metrics.Result actual){
        return byOrder(reference.sales()).equals(byOrder(actual.sales())) &&
            ids(reference.replacements()).equals(ids(actual.replacements()));
    }
    static Metrics.Result checkPublished(Metrics.Query query)throws Exception{
        var reference=Store.exact(query);
        if(reference==null)throw new IOException("缺少相同条件的完整网页参考结果");
        return checkPublished(reference);
    }
    static GroupReports.Verified verifyGroup(Metrics.Query query,boolean force)throws Exception{
        Files.createDirectories(Store.LOCAL);
        try(var channel=java.nio.channels.FileChannel.open(Store.ROOT.resolve("collector.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("采集机正在刷新，请稍后对账");
            try(var zip=new ZipFile(snapshot().toFile())){
                var meta=read(zip,"index.json",Index.class);
                var known=force?null:GroupReports.find(query,meta.reportDate);if(known!=null)return known;
                var pending=Store.LOCAL.resolve("pending").resolve(Store.key(query)+".json");
                Store.save(pending,query);
                var reference=Store.fetch(query,System.out::println);
                GroupReports.Verified verified;
                if(!Objects.equals(reference.reportDate(),meta.reportDate)||query.start().compareTo(meta.start)<0||query.end().compareTo(meta.end)>0)
                    verified=GroupReports.save(reference,"网页订单报表");
                else verified=certify(zip,meta,reference);
                Files.deleteIfExists(pending);
                return verified;
            }
        }
    }
    static Metrics.Result checkPublished(Metrics.Result reference)throws Exception{
        var query=reference.query();
        try(var zip=new ZipFile(snapshot().toFile())){
            var meta=read(zip,"index.json",Index.class);
            if(meta.reportDate==null||!meta.reportDate.equals(reference.reportDate()))throw new IOException("集中批次和网页参考结果的数据更新日不同");
            if(query.start().compareTo(meta.start)<0 || query.end().compareTo(meta.end)>0)throw new IOException("集中批次未覆盖完整查询范围");
            compare(zip,meta,reference);return reference;
        }
    }
    record OrderKey(String order,String date,String warehouse){}
    static Map<OrderKey,Grain.Qty> byOrder(List<Metrics.Line> lines){
        var result=new HashMap<OrderKey,Grain.Qty>();for(var l:lines)result.merge(new OrderKey(l.order(),l.date(),l.warehouse()),new Grain.Qty(l.shipped(),l.returned()),Grain.Qty::plus);return result;
    }
    static Set<OrderKey> ids(List<Metrics.Line> lines){
        var result=new HashSet<OrderKey>();for(var l:lines)result.add(new OrderKey(l.order(),l.date(),l.warehouse()));return result;
    }
    static boolean inRange(Metrics.Line row,Metrics.Query q){
        return row.date().compareTo(q.start())>=0 && row.date().compareTo(q.end())<=0 && q.includesWarehouse(row.warehouse());
    }
}
