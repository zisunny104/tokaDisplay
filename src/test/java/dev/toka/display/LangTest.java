package dev.toka.display;
import dev.toka.i18n.Messages;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.Assert.*;
public class LangTest {
    private static void flatten(String prefix,Object node,Map<String,Object> out){
        if(node instanceof Map<?,?> map)for(var e:map.entrySet())flatten(prefix.isEmpty()?String.valueOf(e.getKey()):prefix+"."+e.getKey(),e.getValue(),out);
        else out.put(prefix,node);
    }
    private static Messages messages(List<String> warnings)throws Exception{
        return new Messages(LangTest.class.getClassLoader(),Files.createTempDirectory("lang"),"zh-TW",warnings::add);
    }
    @Test public void everyKeyInFileIsReadable()throws Exception{
        Map<String,Object> keys=new TreeMap<>();
        try(InputStream in=LangTest.class.getClassLoader().getResourceAsStream("lang/zh-TW.yml")){assertNotNull(in);flatten("",new Yaml().load(in),keys);}
        assertFalse(keys.isEmpty());
        List<String> warnings=new ArrayList<>();Messages m=messages(warnings);
        for(String key:keys.keySet()){assertTrue(key,m.has(key));assertNotEquals(key,m.text(key));}
    }
    @Test public void sourceKeysExistInFile()throws Exception{
        Pattern pattern=Pattern.compile("(?:text|lines)\\(\"([a-z][A-Za-z0-9.]*)\"");
        Path root=Paths.get("src/main/java");
        Set<String> used=new TreeSet<>();
        try(Stream<Path> files=Files.walk(root)){
            for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
                Matcher match=pattern.matcher(Files.readString(file));
                while(match.find())used.add(match.group(1));
            }
        }
        assertFalse(used.isEmpty());
        Messages m=messages(new ArrayList<>());
        for(String key:used)assertTrue("missing key "+key,m.has(key));
    }
}
