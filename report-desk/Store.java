import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

final class Store {
    static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    static final Path APP=Path.of(System.getProperty("desk.app",".")).toAbsolutePath().normalize();
    static final Path LOCAL=Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("user.home")),"AfterSalesDesk");
    static final Path ROOT=Path.of(System.getenv().getOrDefault("AFTERSALES_STORE",LOCAL.resolve("data").toString()));
    record Watch(String code,String warehouse,List<String> selectedSkus) {
        Watch(Metrics.Query q){this(q.code(),q.warehouse(),q.selectedSkus());}
    }
    static String key(Object q) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(q).getBytes(StandardCharsets.UTF_8)));
    }
    static <T>T read(Path file,Class<T> type) throws IOException {
        try(var r=Files.newBufferedReader(file)){return JSON.fromJson(r,type);}
    }
    static void save(Path target,Object object) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp=Files.createTempFile(target.getParent(),".publish-",".tmp");
        try {
            try(var w=Files.newBufferedWriter(tmp)){JSON.toJson(object,w);}
            Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(tmp);}
    }
    static Path cache(Metrics.Query q)throws Exception{return ROOT.resolve("results").resolve(key(q)+".json");}
    static Metrics.Result exact(Metrics.Query q)throws Exception{
        var p=cache(q);return Files.exists(p)?read(p,Metrics.Result.class):null;
    }
    static Metrics.Result last(Metrics.Query q)throws Exception{
        var result=exact(q); if(result!=null)return result;
        var p=ROOT.resolve("latest").resolve(key(new Watch(q))+".json");
        return Files.exists(p)?exact(read(p,Metrics.Query.class)):null;
    }
    static void watch(Watch w,boolean on)throws Exception{
        var p=ROOT.resolve("watch").resolve(key(w)+".json");if(on)save(p,w);else Files.deleteIfExists(p);
    }
    static boolean watched(Watch w)throws Exception{return Files.exists(ROOT.resolve("watch").resolve(key(w)+".json"));}
    static Set<String> aliases(String code)throws IOException{
        var path=APP.resolve("remark-aliases.json");
        JsonElement group=read(path,JsonObject.class).get(Metrics.norm(code));
        var aliases=new LinkedHashSet<String>();
        if(group!=null) for(var a:group.getAsJsonArray()) if(!a.getAsString().isBlank())aliases.add(a.getAsString());
        return aliases;
    }
    static Set<String> aliases(Metrics.Query q)throws IOException{
        return q.selectedSkus()==null?aliases(q.code()):Set.of();
    }
    static List<String> options(Metrics.Query q,String kind,Consumer<String> progress)throws Exception{
        Files.createDirectories(LOCAL);
        Files.createDirectories(ROOT);
        try(var channel=FileChannel.open(ROOT.resolve("collector.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()){
            if(lock==null)throw new IOException("采集机正在刷新，请稍后重试");
            var tmp=Files.createTempDirectory(LOCAL,"options-");
            try{
                save(tmp.resolve("query.json"),q);
                runBrowser(tmp,List.of(kind),progress);
                return List.of(read(tmp.resolve("scope.json"),String[].class));
            }finally{
                Files.deleteIfExists(tmp.resolve("query.json"));
                Files.deleteIfExists(tmp.resolve("scope.json"));
                Files.deleteIfExists(tmp);
            }
        }
    }
    static List<String> warehouseOptions()throws Exception{
        var names=new TreeSet<String>();
        if(Files.exists(Bulk.snapshot()))try(var zip=new java.util.zip.ZipFile(Bulk.snapshot().toFile())){
            var index=Bulk.read(zip,"index.json",Bulk.Index.class);
            for(var part:index.parts()) addWarehouses(names,Bulk.read(zip,part,Metrics.Result.class));
        }
        var dir=ROOT.resolve("results");
        if(Files.isDirectory(dir))try(var paths=Files.list(dir)){
            for(var p:paths.filter(f->f.toString().endsWith(".json")).toList())addWarehouses(names,read(p,Metrics.Result.class));
        }
        var catalog=ROOT.resolve("warehouses.json");
        if(Files.exists(catalog))names.addAll(List.of(read(catalog,String[].class)));
        return List.copyOf(names);
    }
    static void addWarehouses(Set<String> names,Metrics.Result result){
        for(var lines:List.of(result.sales(),result.replacements()))
            for(var row:lines)if(!row.warehouse().isBlank()&&!row.warehouse().equals(Metrics.ALL))names.add(row.warehouse());
    }
    static Metrics.Result fetch(Metrics.Query q,Consumer<String> progress)throws Exception{
        return fetch(q,progress,false);
    }
    static Metrics.Result fetch(Metrics.Query q,Consumer<String> progress,boolean bulk)throws Exception{
        if(q.warehouses().size()>1){
            var parts=new ArrayList<Metrics.Result>();
            for(String name:q.warehouses()){
                progress.accept("正在采集发货仓 "+name);
                parts.add(fetch(new Metrics.Query(q.code(),q.start(),q.end(),name,q.selectedSkus()),progress,bulk));
            }
            return combine(q,parts);
        }
        try{return fetch(q,progress,bulk,null);}
        catch(IllegalArgumentException e){
            if(!String.valueOf(e.getMessage()).contains("导出包含超出查询日期或仓库"))throw e;
            progress.accept("下载内容与筛选范围不符，丢弃本次文件并重新生成报表");
            return fetch(q,progress,bulk,null);
        }
    }
    static Metrics.Result fetch(Metrics.Query q,Consumer<String> progress,boolean bulk,BatchBrowser browser)throws Exception{
        var tmp=Files.createTempDirectory(LOCAL,"batch-");
        try{
            var request=JSON.toJsonTree(q).getAsJsonObject();request.addProperty("bulk",bulk);
            save(tmp.resolve("query.json"),request);
            if(browser==null)runBrowser(tmp,List.of(),progress);
            else browser.collect(tmp.resolve("query.json"),progress);
            var sales=read(tmp.resolve("sales.json"),Metrics.Proof.class);var resend=read(tmp.resolve("reships.json"),Metrics.Proof.class);
            if(!bulk)return Metrics.build(q,tmp,sales,resend,aliases(q));
            if(!Objects.equals(sales.reportDate(),resend.reportDate()))throw new IOException("报表数据更新时间不同");
            return new Metrics.Result(q,Instant.now().toString(),sales.reportDate(),sales.skus(),
                Metrics.read(q,tmp.resolve("sales.xlsx"),sales,false,List.of(),Set.of(),true),
                Metrics.read(q,tmp.resolve("reships.xlsx"),resend,true,List.of(),Set.of(),true));
        } finally {
            // Only files created by this batch in the unique local staging directory.
            for(String name:List.of("query.json","sales.json","reships.json","sales.xlsx","reships.xlsx"))
                Files.deleteIfExists(tmp.resolve(name));
            Files.deleteIfExists(tmp);
        }
    }
    static Metrics.Result combine(Metrics.Query q,List<Metrics.Result> parts)throws IOException{
        var first=parts.get(0);
        var skuKeys=new HashSet<>(first.skus().stream().map(Metrics::norm).toList());
        var sales=new ArrayList<Metrics.Line>();var replacements=new ArrayList<Metrics.Line>();
        for(var part:parts){
            if(!Objects.equals(first.reportDate(),part.reportDate()) ||
                !skuKeys.equals(new HashSet<>(part.skus().stream().map(Metrics::norm).toList())))
                throw new IOException("分批报表的商品清单或数据更新时间不同，请重新查询");
            sales.addAll(part.sales());replacements.addAll(part.replacements());
        }
        return new Metrics.Result(q,Instant.now().toString(),first.reportDate(),first.skus(),sales,replacements);
    }
    static void runBrowser(Path tmp,List<String> options,Consumer<String> progress)throws Exception{
        var args=new ArrayList<>(List.of(APP.resolve("driver/node.exe").toString(),APP.resolve("browser.cjs").toString(),tmp.resolve("query.json").toString()));args.addAll(options);
        var proc=new ProcessBuilder(args).directory(APP.toFile()).redirectErrorStream(true).start();String last="浏览器任务失败";
        try{
            try(var r=proc.inputReader(StandardCharsets.UTF_8)){for(String msg;(msg=r.readLine())!=null;){last=msg;progress.accept(msg);}}
            if(proc.waitFor()!=0)throw new IOException(last);
        }finally{if(proc.isAlive())proc.destroy();}
    }
    static Metrics.Result refresh(Metrics.Query q,boolean monthly,Consumer<String> progress)throws Exception{
        Files.createDirectories(LOCAL);Files.createDirectories(ROOT);
        try(var channel=FileChannel.open(ROOT.resolve("collector.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()){
            if(lock==null)throw new IOException("采集机正在刷新，请稍后重试");
            var pending=LOCAL.resolve("pending").resolve(key(q)+".json");
            save(pending,q);
            Metrics.Result result;
            if(monthly) {
                LocalDate end=LocalDate.parse(q.end());
                var parts=new ArrayList<Metrics.Result>();
                for(var start=LocalDate.parse(q.start());!start.isAfter(end);){
                    var stop=start.withDayOfMonth(start.lengthOfMonth());if(stop.isAfter(end))stop=end;
                    progress.accept("刷新发货区间 "+start+" 至 "+stop);
                    parts.add(fetch(new Metrics.Query(q.code(),start.toString(),stop.toString(),q.warehouse(),q.selectedSkus()),progress));
                    start=stop.plusDays(1);
                }
                result=combine(q,parts);
            } else result=fetch(q,progress);
            save(cache(q),result);
            GroupReports.save(result,"网页订单报表");
            save(ROOT.resolve("latest").resolve(key(new Watch(q))+".json"),q);
            Files.deleteIfExists(pending);
            return result;
        }catch(OverlappingFileLockException e){throw new IOException("已有查询正在执行");}
    }
    static void daily()throws Exception{
        Exception bulkFailure=null;
        boolean published=false;
        if(Bulk.enabled())try{Bulk.collect(System.out::println);published=true;}catch(Exception e){
            bulkFailure=e;System.err.println("全商品批次未发布，转为刷新关注商品："+e.getMessage());
        }
        var folder=ROOT.resolve("watch");if(!Files.isDirectory(folder)){if(bulkFailure!=null)throw bulkFailure;System.out.println("尚未关注商品");return;}
        int failed=0;
        try(var paths=Files.list(folder)){
            for(var path:paths.filter(p->p.toString().endsWith(".json")).toList())try{
                var watch=read(path,Watch.class);var rolling=Metrics.Query.rolling(watch.code,watch.warehouse);
                var q=new Metrics.Query(rolling.code(),rolling.start(),rolling.end(),rolling.warehouse(),watch.selectedSkus);
                if(published)Bulk.query(q);else refresh(q,true,System.out::println);
            }catch(Exception e){System.err.println(e.getMessage());failed++;}
        }
        if(failed>0)throw new IOException(failed+" 个关注商品未完成，保留先前数据");
        if(bulkFailure!=null)throw new IOException("关注商品已刷新，全商品批次仍待重试",bulkFailure);
    }
}
