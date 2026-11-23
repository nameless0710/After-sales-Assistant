import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One private browser for all date ranges of one scheduled refresh. */
final class BatchBrowser implements AutoCloseable {
    private Process process;
    private BufferedWriter commands;
    private BufferedReader events;

    void collect(Path request,Consumer<String> progress)throws IOException{
        if(process==null){
            process=new ProcessBuilder(Store.APP.resolve("driver/node.exe").toString(),Store.APP.resolve("browser.cjs").toString(),
                request.toString(),"--batch-session").directory(Store.APP.toFile()).redirectErrorStream(true).start();
            commands=process.outputWriter(StandardCharsets.UTF_8);events=process.inputReader(StandardCharsets.UTF_8);
        }else{
            commands.write(Store.JSON.toJson(request.toString()));commands.newLine();commands.flush();
        }
        String last="浏览器未返回完整的采集结果";
        for(String line;(line=events.readLine())!=null;){
            if(line.equals("BATCH_DONE"))return;
            if(line.startsWith("BATCH_FAILED "))throw new IOException(line.substring("BATCH_FAILED ".length()));
            last=line;progress.accept(line);
        }
        throw new IOException(last);
    }

    public void close()throws IOException{
        if(process==null)return;
        try{
            if(process.isAlive()){commands.write("END_BATCH");commands.newLine();commands.flush();}
        }finally{
            try{commands.close();}finally{
                try{if(!process.waitFor(20,TimeUnit.SECONDS))process.destroy();}
                catch(InterruptedException ex){Thread.currentThread().interrupt();process.destroy();}
                finally{events.close();}
            }
        }
    }
}
