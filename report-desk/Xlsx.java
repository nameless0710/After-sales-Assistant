import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import javax.xml.stream.*;

/** Read only the selected columns. Buyer and address columns are never persisted. */
final class Xlsx {
    static final Set<String> FIELDS = Set.of("内部订单号","发货仓","发货日期","商品编码","订单商品明细","实发数量","退货数量","卖家备注");
    static XMLStreamReader xml(InputStream input) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        return factory.createXMLStreamReader(input);
    }
    static void read(Path file, Set<String> required, Consumer<Map<String,String>> consume) throws Exception {
        try (var zip = new ZipFile(file.toFile())) {
            var sheets = zip.stream().filter(e -> e.getName().matches("xl/worksheets/sheet[0-9]+\\.xml")).toList();
            if (sheets.size() != 1) throw new IOException("请使用单工作表的完整订单导出文件");
            List<String> strings = new ArrayList<>();
            var shared = zip.getEntry("xl/sharedStrings.xml");
            if (shared != null) try (var in=zip.getInputStream(shared)) {
                var r=xml(in); var buf=new StringBuilder();
                while (r.hasNext()) {
                    int ev=r.next();
                    if (ev==1 && r.getLocalName().equals("si")) buf.setLength(0);
                    if (ev==1 && r.getLocalName().equals("t")) buf.append(r.getElementText());
                    if (ev==2 && r.getLocalName().equals("si")) strings.add(buf.toString());
                }
                r.close();
            }
            try (var in=zip.getInputStream(sheets.get(0))) {
                var r=xml(in); Map<String,String> columns=new HashMap<>(), row=new HashMap<>();
                boolean header=true, capture=false; String col="", type=null; var value=new StringBuilder();
                while (r.hasNext()) {
                    int ev=r.next();
                    if (ev!=1 && ev!=2) continue;
                    String tag=r.getLocalName();
                    if (ev==1 && tag.equals("row")) row.clear();
                    if (ev==1 && tag.equals("c")) {
                        col=r.getAttributeValue(null,"r").replaceAll("[0-9]+","");
                        type=r.getAttributeValue(null,"t"); capture=header || columns.containsKey(col); value.setLength(0);
                    }
                    if (ev==1 && capture && (tag.equals("v") || tag.equals("t"))) value.append(r.getElementText());
                    if (ev==2 && tag.equals("c") && capture) {
                        String v=value.toString();
                        if ("s".equals(type) && !v.isEmpty()) v=strings.get(Integer.parseInt(v));
                        v=v.strip();
                        if (header && FIELDS.contains(v)) {
                            if(columns.containsValue(v)) throw new IOException("导出含重复列 "+v);
                            columns.put(col,v);
                        } else if (!header && !v.isEmpty()) row.put(columns.get(col),v);
                    }
                    if (ev==2 && tag.equals("row")) {
                        if (header) {
                            if (!columns.values().containsAll(required)) {
                                var missing=new TreeSet<>(required); missing.removeAll(columns.values());
                                throw new IOException("导出缺少列 "+missing);
                            }
                            header=false;
                        } else if (!row.isEmpty()) consume.accept(row);
                    }
                }
                r.close();
                if (header) throw new IOException("导出文件没有表头");
            }
        }
    }
}
