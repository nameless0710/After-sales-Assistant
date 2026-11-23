import java.awt.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelListener;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

public final class Desk {
    final JFrame frame=new JFrame("商品售后查询台");
    final JTextField code=new JTextField(22);
    final JButton warehouse=new JButton(Metrics.ALL);
    String warehouseValue=Metrics.ALL, selectionCode="";
    List<String> selectedSkus;
    final Map<String,List<String>> productChoices=new HashMap<>();
    final JTextField start=new JTextField(10),end=new JTextField(10);
    final JButton search=new JButton("查询"), refresh=new JButton("重新采集"), watch=new JButton("每日关注");
    final JLabel status=new JLabel("输入商品编码，按回车查询。"), source=new JLabel(" "), formula=new JLabel(" ");
    final JButton[] cards={new JButton("实发件数"),new JButton("退货件数"),new JButton("补发单数"),new JButton("售后率")};
    final JButton products=new JButton("匹配商品");
    Metrics.Result result; boolean busy=false;
    Desk()throws Exception{
        var today=Metrics.Query.rolling("商品",Metrics.ALL);start.setText(today.start());end.setText(today.end());
        var panel=new JPanel();panel.setBorder(BorderFactory.createEmptyBorder(16,18,14,18));
        panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
        panel.add(row(new JLabel("商品编码"),code,products,search,refresh,watch));
        panel.add(row(new JLabel("发货日期"),start,new JLabel("至"),end,new JLabel("发货仓"),warehouse));
        var metrics=new JPanel(new GridLayout(1,4,12,0));metrics.setBorder(BorderFactory.createEmptyBorder(16,0,12,0));
        for(int i=0;i<4;i++){final int k=i;metrics.add(cards[i]);cards[i].setEnabled(false);cards[i].addActionListener(e->details(k));}
        panel.add(metrics);panel.add(row(formula));panel.add(row(source));
        products.addActionListener(e->chooseProducts());
        warehouse.addActionListener(e->chooseWarehouses(false));
        code.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}
            void changed(){if(!Metrics.norm(code.getText()).equals(selectionCode)){selectedSkus=null;products.setText("匹配商品（默认全选）");}}
        });
        panel.add(row(status));
        search.addActionListener(e->query(false));refresh.addActionListener(e->query(true));
        watch.addActionListener(e->{try{var q=query();var w=new Store.Watch(q);boolean on=!Store.watched(w);Store.watch(w,on);
            status.setText((on?"已加入每日关注清单 ":"已取消关注 ")+q.code()+" · "+q.warehouseLabel());}catch(Exception ex){error(ex);}});
        var menu=new JMenuBar();var ops=new JMenu("数据");menu.add(ops);
        var daily=ops.add("每日自动刷新…");daily.addActionListener(e->manageDaily(daily,"Status"));
        var retry=ops.add("恢复未完成的查询");retry.addActionListener(e->resume());
        var check=ops.add("查看案例一");check.addActionListener(e->example());
        frame.setJMenuBar(menu);frame.setContentPane(panel);frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getRootPane().setDefaultButton(search);frame.pack();frame.setMinimumSize(new Dimension(830,320));frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if(Files.isDirectory(Store.LOCAL.resolve("pending")))try(var paths=Files.list(Store.LOCAL.resolve("pending"))){
            if(paths.anyMatch(p->p.toString().endsWith(".json")))status.setText("有未完成任务，可从「数据 → 恢复未完成的查询」继续。");
        }
    }
    void manageDaily(JMenuItem menuItem,String mode){
        menuItem.setEnabled(false);
        new SwingWorker<String,Void>(){
            @Override protected String doInBackground()throws Exception{
                var script=Store.APP.resolve("启用每日刷新.ps1");
                var process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","RemoteSigned","-File",
                    script.toString(),"-Mode",mode).redirectErrorStream(true).start();
                String output;
                try(var stream=process.getInputStream()){
                    output=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
                }
                if(process.waitFor()!=0)throw new java.io.IOException(output);
                return output;
            }
            @Override protected void done(){
                menuItem.setEnabled(true);
                try{
                    String message=get();
                    if(!mode.equals("Status")){
                        JOptionPane.showMessageDialog(frame,message,"每日自动刷新",JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    String[] options={"开启或重设为 08:30","关闭","返回"};
                    int choice=JOptionPane.showOptionDialog(frame,new Object[]{message,
                        "这是 Windows 计划任务，即使查询台关闭也可以启动。",
                        "刷新可能分段导出全商品报表。关闭会停用后续触发。",
                        "重新设置后，错过当天的运行时间时会等到下一次计划。"},
                        "每日自动刷新",JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,options,options[2]);
                    if(choice==0)manageDaily(menuItem,"Enable");
                    if(choice==1)manageDaily(menuItem,"Disable");
                }catch(Exception ex){
                    var cause=ex.getCause()==null?ex:ex.getCause();
                    var detail=new JTextArea(Objects.toString(cause.getMessage(),"无法访问 Windows 计划任务"),6,58);
                    detail.setEditable(false);detail.setWrapStyleWord(true);detail.setLineWrap(true);
                    JOptionPane.showMessageDialog(frame,new JScrollPane(detail),"无法管理每日自动刷新",JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }
    static JPanel row(Component... components){var row=new JPanel(new FlowLayout(FlowLayout.LEFT,8,5));for(var c:components)row.add(c);return row;}
    Metrics.Query query(){return new Metrics.Query(code.getText(),start.getText().strip(),end.getText().strip(),warehouseValue,selectedSkus);}
    void controls(boolean enabled){busy=!enabled;for(var c:List.of(code,start,end,warehouse,products,search,refresh,watch))c.setEnabled(enabled);}
    void clear(){result=null;for(var b:cards)b.setEnabled(false);String[] titles={"实发件数","退货件数","补发单数","售后率"};for(int i=0;i<4;i++)cards[i].setText(titles[i]);source.setText(" ");formula.setText(" ");}
    void setWarehouses(String value){
        warehouseValue=value;
        var names=value.split("\n");
        warehouse.setText(names.length==1?value:"已选 "+names.length+" 个仓库");
        warehouse.setToolTipText(String.join("、",names));
    }
    void setProducts(String keyword,List<String> selection){
        selectionCode=Metrics.norm(keyword);selectedSkus=selection;
        products.setText(selection==null?"匹配商品（默认全选）":"已选 "+selection.size()+" 项商品");
    }
    void chooseWarehouses(boolean sync){
        loadOptions("正在读取发货仓…",progress->{
            var names=new TreeSet<>(Store.warehouseOptions());
            if(sync || names.isEmpty()){
                var live=Store.options(Metrics.Query.rolling("*",Metrics.ALL),"--warehouses",progress);
                if(live.isEmpty())throw new java.io.IOException("ERP 未返回仓库选项");
                Store.save(Store.ROOT.resolve("warehouses.json"),live);
                names.addAll(live);
            }
            if(!warehouseValue.equals(Metrics.ALL))names.addAll(List.of(warehouseValue.split("\n")));
            return List.copyOf(names);
        },names->{
            var selected=warehouseValue.equals(Metrics.ALL)?names:List.of(warehouseValue.split("\n"));
            choose("选择发货仓",names,selected,true,values->setWarehouses(values==null?Metrics.ALL:String.join("\n",values)),()->chooseWarehouses(true));
        });
    }
    void chooseProducts(){
        if(code.getText().isBlank()){
            JOptionPane.showMessageDialog(frame,"请先输入商品编码");return;
        }
        try{
            var q=query();
            var keyword=Metrics.norm(q.code());
            var known=productChoices.get(keyword);
            if(known!=null){editProducts(q.code(),known);return;}
            loadOptions("正在读取匹配商品…",progress->{
                var basis=new Metrics.Query(q.code(),q.start(),q.end(),Metrics.ALL);
                return Store.options(basis,"--catalog",progress);
            },names->{productChoices.put(keyword,names);editProducts(q.code(),names);});
        }catch(Exception e){error(e);}
    }
    void editProducts(String keyword,List<String> names){
        choose("选择匹配商品",names,selectedSkus==null?names:selectedSkus,false,
            values->setProducts(keyword,values.size()==names.size()?null:values),null);
    }
    interface OptionsLoader{
        List<String> load(java.util.function.Consumer<String> progress)throws Exception;
    }
    void loadOptions(String message,OptionsLoader loader,java.util.function.Consumer<List<String>> onLoaded){
        controls(false);status.setText(message);
        new SwingWorker<List<String>,String>(){
            protected List<String> doInBackground()throws Exception{return loader.load(msg->publish(msg));}
            protected void process(List<String> messages){status.setText(messages.get(messages.size()-1));}
            protected void done(){controls(true);try{var items=get();status.setText(" ");onLoaded.accept(items);}catch(Exception e){error(e);}}
        }.execute();
    }
    void choose(String title,List<String> items,List<String> selected,boolean allowAll,
        java.util.function.Consumer<List<String>> accept,Runnable sync){
        var dialog=new JDialog(frame,title,true);
        var model=new DefaultTableModel(new Object[]{"选择",allowAll?"发货仓":"商品编码"},0){
            public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}
            public boolean isCellEditable(int row,int col){return col==0;}
        };
        var keys=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);keys.addAll(selected);
        for(String item:items)model.addRow(new Object[]{keys.contains(item),item});
        var grid=new JTable(model);
        grid.getColumnModel().getColumn(0).setMaxWidth(64);
        var count=new JLabel();
        Runnable update=()->{
            int checked=0;for(int i=0;i<model.getRowCount();i++)if(Boolean.TRUE.equals(model.getValueAt(i,0)))checked++;
            count.setText("匹配 "+grid.getRowCount()+" / "+items.size()+"    已选 "+checked+" / "+items.size());
        };
        TableModelListener selectionChanged=e->update.run();
        model.addTableModelListener(selectionChanged);
        java.util.function.Consumer<Boolean> setVisibleSelection=checked->{
            if(grid.isEditing())grid.getCellEditor().stopCellEditing();
            model.removeTableModelListener(selectionChanged);
            try{
                for(int viewRow=0;viewRow<grid.getRowCount();viewRow++)
                    model.setValueAt(checked,grid.convertRowIndexToModel(viewRow),0);
            }finally{model.addTableModelListener(selectionChanged);}
            update.run();
        };
        var all=new JButton("勾选筛选结果");
        var none=new JButton("取消勾选筛选结果");
        var searchField=new JTextField(22);
        var clearSearch=new JButton("清除搜索");
        clearSearch.addActionListener(e->{searchField.setText("");searchField.requestFocusInWindow();});
        var sorter=new TableRowSorter<>(model);
        grid.setRowSorter(sorter);
        searchField.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){filter();}
            public void removeUpdate(DocumentEvent e){filter();}
            public void changedUpdate(DocumentEvent e){filter();}
            void filter(){
                if(grid.isEditing())grid.getCellEditor().stopCellEditing();
                String keyword=Metrics.norm(searchField.getText());
                sorter.setRowFilter(keyword.isEmpty()?null:new RowFilter<DefaultTableModel,Integer>(){
                    public boolean include(Entry<? extends DefaultTableModel,? extends Integer> entry){
                        return Metrics.norm(entry.getStringValue(1)).contains(keyword);
                    }
                });
                all.setEnabled(grid.getRowCount()>0);
                none.setEnabled(grid.getRowCount()>0);
                update.run();
            }
        });
        dialog.add(row(new JLabel(allowAll?"搜索仓库名称":"搜索商品编码"),searchField,clearSearch,count),BorderLayout.NORTH);
        dialog.addWindowListener(new java.awt.event.WindowAdapter(){
            public void windowOpened(java.awt.event.WindowEvent e){searchField.requestFocusInWindow();}
        });
        update.run();
        all.addActionListener(e->setVisibleSelection.accept(true));
        none.addActionListener(e->setVisibleSelection.accept(false));
        var confirm=new JButton("确定");
        confirm.addActionListener(e->{
            if(grid.isEditing())grid.getCellEditor().stopCellEditing();
            var values=new ArrayList<String>();
            for(int i=0;i<items.size();i++)if(Boolean.TRUE.equals(model.getValueAt(i,0)))values.add(items.get(i));
            if(values.isEmpty()){JOptionPane.showMessageDialog(dialog,allowAll?"请勾选发货仓，或点击「所有仓库」":"请至少勾选一项商品");return;}
            accept.accept(List.copyOf(values));dialog.dispose();
        });
        var cancel=new JButton("取消");cancel.addActionListener(e->dialog.dispose());
        var actions=row(all,none);
        if(allowAll){
            var allWarehouses=new JButton("所有仓库");
            allWarehouses.addActionListener(e->{accept.accept(null);dialog.dispose();});
            actions.add(allWarehouses);
        }
        actions.add(confirm);actions.add(cancel);
        if(sync!=null){
            var updateList=new JButton("从 ERP 更新");
            updateList.addActionListener(e->{dialog.dispose();sync.run();});actions.add(updateList);
        }
        dialog.add(new JScrollPane(grid));dialog.add(actions,BorderLayout.SOUTH);
        dialog.setSize(680,460);dialog.setLocationRelativeTo(frame);dialog.setVisible(true);
    }
    void query(boolean force){
        if(busy)return;
        try {
            var q=query();var previous=result;clear();
            if(previous!=null && previous.query().equals(q))show(previous);
            if(!force && Bulk.enabled()){
                controls(false);
                new SwingWorker<Metrics.Result,Void>(){
                    String origin="网页订单报表";
                    protected Metrics.Result doInBackground()throws Exception{
                        var exact=Store.exact(q);var group=Bulk.query(q);var bulk=group==null?null:group.result();
                        if(bulk==null)return exact!=null && Instant.parse(exact.collected()).atZone(Metrics.ZONE).toLocalDate().equals(LocalDate.now(Metrics.ZONE))?exact:null;
                        if(exact!=null && Instant.parse(exact.collected()).isAfter(Instant.parse(bulk.collected())))return exact;
                        origin=group.source();return bulk;
                    }
                    protected void done(){controls(true);try{var r=get();if(r==null){query(true);return;}show(r);status.setText(origin+" · 点击数字查看订单");}catch(Exception ex){try{if(result==null){var old=Store.last(q);if(old!=null)show(old);}}catch(Exception ignored){}error(ex);}}
                }.execute();return;
            }
            var old=Store.last(q);
            if(old!=null && (result==null || Instant.parse(old.collected()).isAfter(Instant.parse(result.collected()))))show(old);
            if(!force && old!=null && old.query().equals(q) &&
               Instant.parse(old.collected()).atZone(Metrics.ZONE).toLocalDate().equals(LocalDate.now(Metrics.ZONE))){
                status.setText("已读取今天校验通过的缓存");return;
            }
            controls(false);status.setText("正在启动专用浏览器…");
            new SwingWorker<Metrics.Result,String>(){
                protected Metrics.Result doInBackground()throws Exception{return Store.refresh(q,false,msg->publish(msg));}
                protected void process(List<String> messages){status.setText(messages.get(messages.size()-1));}
                protected void done(){controls(true);try{show(get());status.setText("已完成，点击数字查看订单");}catch(Exception ex){error(ex);}}
            }.execute();
        }catch(Exception e){error(e);}
    }
    void show(Metrics.Result r){
        result=r;String[] titles={"实发 "+r.shipped()+" 件","退货 "+r.returned()+" 件","补发 "+r.reships()+" 单","售后率 "+r.rate()};
        for(int i=0;i<4;i++){cards[i].setText(titles[i]);cards[i].setEnabled(true);}
        if(qMatchesPicker(r.query())){
            products.setText((selectedSkus==null?"匹配商品 ":"已选 ")+r.skus().size()+" 项");
            if(selectedSkus==null)productChoices.put(Metrics.norm(r.query().code()),r.skus());
        }
        formula.setText("（退货 "+r.positive(false)+" 单 ＋ 补发 "+r.reships()+" 单）÷ 实发 "+r.positive(true)+" 单");
        var q=r.query();String wh=q.warehouses().size()>1?q.warehouses().size()+" 个仓库":q.warehouseLabel();
        source.setToolTipText(q.warehouseLabel());source.setText(q.code()+" · "+wh+" · "+q.start()+" 至 "+q.end()+"    数据更新至 "+
            (r.reportDate()==null?"网页未标明":r.reportDate())+"    采集 "+Instant.parse(r.collected()).atZone(Metrics.ZONE).toLocalDateTime().withNano(0));
    }
    boolean qMatchesPicker(Metrics.Query query){
        return Metrics.norm(code.getText()).equals(Metrics.norm(query.code()))&&Objects.equals(selectedSkus,query.selectedSkus());
    }
    void error(Exception ex){Throwable t=ex;while(t.getCause()!=null)t=t.getCause();String msg=Objects.toString(t.getMessage(),t.getClass().getSimpleName());
        status.setText(result==null?"查询未完成":"刷新未完成，正在展示上次成功结果");JOptionPane.showMessageDialog(frame,msg,"查询未完成",JOptionPane.WARNING_MESSAGE);}
    static final String[] COLS={"类别","内部订单号","发货日期","发货仓","商品编码 / 订单商品明细","实发件数","退货件数","备注命中词"};
    static String[] cells(Metrics.Line l,boolean resend){return new String[]{resend?"补发":"销售",l.order(),l.date(),l.warehouse(),
        l.sku().isEmpty()?"订单报表未提供逐行 SKU":l.sku(),resend?"":""+l.shipped(),resend?"":""+l.returned(),l.keyword()};}
    void details(int k){
        var rows=new ArrayList<String[]>();if(k!=2)for(var l:result.sales())if(k==3 || (k==0?l.shipped():l.returned())!=0)rows.add(cells(l,false));
        if(k>=2)for(var l:result.replacements())rows.add(cells(l,true));
        table(cards[k].getText()+" · 订单明细",COLS,rows);
    }
    void table(String title,String[] cols,List<String[]> rows){
        var d=new JDialog(frame,title,true);var model=new DefaultTableModel(rows.toArray(String[][]::new),cols){
            public boolean isCellEditable(int r,int c){return false;}};
        var grid=new JTable(model);grid.setAutoCreateRowSorter(true);grid.setCellSelectionEnabled(true);
        var save=new JButton("导出当前明细");save.addActionListener(e->{
            var chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("售后明细.csv"));
            if(chooser.showSaveDialog(d)!=JFileChooser.APPROVE_OPTION)return;
            var file=chooser.getSelectedFile().toPath();if(Files.exists(file) && JOptionPane.showConfirmDialog(d,"覆盖已有文件？","保存",JOptionPane.YES_NO_OPTION)!=0)return;
            try(var w=Files.newBufferedWriter(file)){w.write('\ufeff');var lines=new ArrayList<String[]>();lines.add(cols);lines.addAll(rows);
                for(var line:lines){var parts=new ArrayList<String>();for(String v:line){if(v.matches("^[=+@\\-].*"))v="'"+v;parts.add("\""+v.replace("\"","\"\"")+"\"");}w.write(String.join(",",parts));w.newLine();}
            }catch(Exception ex){error(ex);}
        });
        d.add(new JScrollPane(grid));d.add(row(new JLabel(rows.size()+" 行"),save),BorderLayout.SOUTH);
        d.setSize(cols.length>1?970:440,500);d.setLocationRelativeTo(frame);d.setVisible(true);
    }
    void resume(){
        if(busy)return;
        try{
            var dir=Store.LOCAL.resolve("pending");if(!Files.isDirectory(dir)){status.setText("没有未完成任务");return;}
            var tasks=new LinkedHashMap<String,Metrics.Query>();
            try(var files=Files.list(dir)){for(var p:files.filter(f->f.toString().endsWith(".json")).toList()){var q=Store.read(p,Metrics.Query.class);tasks.put(q.code()+" · "+q.warehouseLabel()+" · "+q.start()+" 至 "+q.end()+(q.selectedSkus()==null?"":" · "+String.join("、",q.selectedSkus())),q);}}
            if(tasks.isEmpty()){status.setText("没有未完成任务");return;}
            var label=choosePendingTask(tasks.keySet());
            if(label!=null){var q=tasks.get(label);code.setText(q.code());setProducts(q.code(),q.selectedSkus());setWarehouses(q.warehouse());start.setText(q.start());end.setText(q.end());query(true);}
        }catch(Exception ex){error(ex);}
    }
    String choosePendingTask(Set<String> tasks){
        var picker=new JComboBox<>(tasks.toArray(String[]::new));
        picker.setPrototypeDisplayValue("选择任务");
        picker.setPreferredSize(new Dimension(520,picker.getPreferredSize().height));
        picker.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,
                boolean selected,boolean hasFocus){
                super.getListCellRendererComponent(list,value,index,selected,hasFocus);
                setToolTipText(taskTooltip((String)value));
                return this;
            }
        });
        // The list must receive hover events so Swing can use the row renderer's tooltip.
        if(picker.getAccessibleContext().getAccessibleChild(0) instanceof BasicComboPopup popup)
            ToolTipManager.sharedInstance().registerComponent(popup.getList());
        Runnable updateTooltip=()->picker.setToolTipText(taskTooltip((String)picker.getSelectedItem()));
        picker.addActionListener(e->updateTooltip.run());
        updateTooltip.run();
        int choice=JOptionPane.showConfirmDialog(frame,new Object[]{"选择任务",picker},"恢复查询",
            JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE);
        if(picker.getAccessibleContext().getAccessibleChild(0) instanceof BasicComboPopup popup)
            ToolTipManager.sharedInstance().unregisterComponent(popup.getList());
        return choice==JOptionPane.OK_OPTION?(String)picker.getSelectedItem():null;
    }
    static String taskTooltip(String task){
        if(task==null)return null;
        return "<html><div style='width:480px'>"+task.replace("&","&amp;").replace("<","&lt;")
            .replace(">","&gt;").replace("、","、\u200b").replace(" · "," · <br>")+"</div></html>";
    }
    void example(){
        if(busy)return;
        try{
            var q=new Metrics.Query("龙竹-50-60cm","2026-08-01","2026-11-18",Metrics.ALL);
            var live=Store.exact(q);
            code.setText(q.code());start.setText(q.start());end.setText(q.end());setProducts(q.code(),null);setWarehouses(q.warehouse());
            if(live!=null){
                show(live);status.setText("案例一 · 最近一次完整网页导出，点击数字查看订单");return;
            }
            if(!Files.exists(Store.APP.getParent().resolve("商品选择器请求 案例一.txt"))){query(false);return;}
        }catch(Exception ex){error(ex);return;}
        controls(false);
        new SwingWorker<CaseCheck.Evidence,Void>(){
            protected CaseCheck.Evidence doInBackground()throws Exception{return CaseCheck.run(Store.APP.getParent());}
            protected void done(){controls(true);try{var e=get();status.setText(e.summary());table(e.summary(),COLS,e.hits().stream().map(l->cells(l,true)).toList());}catch(Exception ex){error(ex);}}
        }.execute();
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0){
            switch(args[0]){
                case "--daily" -> Store.daily();
                case "--verify-published","--verify-live" -> {
                    if(args.length!=5)throw new IllegalArgumentException(args[0]+" 商品编码 开始日期 结束日期 发货仓");
                    var q=new Metrics.Query(args[1],args[2],args[3],args[4]);
                    var entry=args[0].equals("--verify-live")?Bulk.verifyGroup(q,true):null;
                    var r=entry==null?Bulk.checkPublished(q):entry.result();
                    System.out.printf("%s。数据更新至 %s，商品 %d 项，实发 %d 件（%d 单），退货 %d 件（%d 单），补发 %d 单，售后率 %s%n",
                        entry==null?"集中批次逐单一致":entry.source(),r.reportDate(),r.skus().size(),r.shipped(),r.positive(true),r.returned(),r.positive(false),r.reships(),r.rate());
                }
                case "--find-pair" -> System.out.println(Store.JSON.toJson(Grain.findPair(Path.of(args[1]))));
                case "--verify-sales-export" -> {var d=Path.of(args[1]);var name=args[2];var q=Store.read(d.resolve("query.json"),Metrics.Query.class);
                    var p=Store.read(d.resolve(name+".json"),Metrics.Proof.class);Metrics.read(q,d.resolve(name+".xlsx"),p,false,p.skus(),Set.of(),name.equals("sales"));}
                case "--verify-grain" -> {if(args.length!=7)throw new IllegalArgumentException("--verify-grain SKU_A SKU_B 订单商品.xlsx A订单.xlsx B订单.xlsx AB订单.xlsx");
                    Grain.verify(args[1],args[2],Path.of(args[3]),Path.of(args[4]),Path.of(args[5]),Path.of(args[6]));System.out.println("逐单对账通过，下一次每日任务将建立全商品批次");}
                case "--case" -> System.out.println(CaseCheck.run(Store.APP.getParent()).summary());
                case "--verify-batch" -> {var d=Path.of(args[1]);var q=Store.read(d.resolve("query.json"),Metrics.Query.class);
                    var r=Metrics.build(q,d,Store.read(d.resolve("sales.json"),Metrics.Proof.class),Store.read(d.resolve("reships.json"),Metrics.Proof.class),Store.aliases(q.code()));
                    System.out.printf("实发 %d 件，退货 %d 件，补发 %d 单，售后率 %s%n",r.shipped(),r.returned(),r.reships(),r.rate());}
                case "--query" -> {if(args.length!=5)throw new IllegalArgumentException("--query 商品编码 开始日期 结束日期 发货仓");var r=Store.refresh(new Metrics.Query(args[1],args[2],args[3],args[4]),false,System.out::println);System.out.printf("实发 %d 件，退货 %d 件，补发 %d 单，售后率 %s%n",r.shipped(),r.returned(),r.reships(),r.rate());}
                default -> throw new IllegalArgumentException("未知参数");
            }return;
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());SwingUtilities.invokeLater(()->{try{new Desk();}catch(Exception e){JOptionPane.showMessageDialog(null,e.toString());}});
    }
}
