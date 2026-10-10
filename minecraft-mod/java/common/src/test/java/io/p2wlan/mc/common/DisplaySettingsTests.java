package io.p2wlan.mc.common;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;

/** Local preferences, native aggregate validation and viewport geometry. */
public final class DisplaySettingsTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("display " + checks); }
    private static com.google.gson.JsonObject population() {
        return JsonParser.parseString("{\"window_ms\":60000,\"capacity\":256,\"samples\":100,\"capacity_limited\":false,\"last_sample_age_ms\":100,\"min_us\":1000,\"avg_us\":50500,\"max_us\":100000,\"p50_us\":50000,\"p90_us\":90000,\"p95_us\":95000,\"p99_us\":99000}").getAsJsonObject();
    }
    private static void reject(com.google.gson.JsonObject value) throws Exception {
        try { LatencyStatistics.parse(value); throw new AssertionError("accepted invalid statistics"); }
        catch (AgentException error) { check(error.code()==AgentException.Code.INVALID_REPLY); }
    }
    private static void statistics() throws Exception {
        var stats=LatencyStatistics.parse(population());
        check(stats.samples()==100 && stats.avgUs()==50500 && stats.p99Us()==99000);
        for (String field:population().keySet()) {
            var value=population();value.remove(field);reject(value);
        }
        for (String key:List.of("window_ms","capacity","samples","min_us","avg_us","max_us","p50_us","p90_us","p95_us","p99_us","last_sample_age_ms")) {
            for (String invalid:List.of("-1","1.5","\"1\"","true","9223372036854775808")) {
                var value=population();value.add(key,JsonParser.parseString(invalid));reject(value);
            }
        }
        for (String entry:List.of("\"window_ms\":1","\"capacity\":1","\"samples\":257","\"last_sample_age_ms\":60001","\"p50_us\":999999","\"avg_us\":100001","\"capacity_limited\":1","\"extra\":1")) {
            var value=population();var change=JsonParser.parseString("{"+entry+"}").getAsJsonObject();change.entrySet().forEach(e->value.add(e.getKey(),e.getValue()));reject(value);
        }
        var empty=population();empty.addProperty("samples",0);empty.add("last_sample_age_ms",com.google.gson.JsonNull.INSTANCE);
        for (String key:List.of("min_us","avg_us","max_us","p50_us","p90_us","p95_us","p99_us")) empty.add(key,com.google.gson.JsonNull.INSTANCE);
        check(LatencyStatistics.parse(empty).samples()==0);
        check(LatencyStatistics.ms(null).equals("—") && LatencyStatistics.ms(0L).equals("<0.001"));
        check(LatencyStatistics.ms(5L).equals("0.005") && LatencyStatistics.ms(100L).equals("0.1"));
        check(LatencyStatistics.ms(50500L).equals("50.5") && LatencyStatistics.ms(17000L).equals("17"));
        check(new LatencyStatistics(1,false,0L,0L,0L,0L,0L,0L,0L,0L).tooltip().contains("不足 100"));
    }
    private static void preferences() throws Exception {
        var dir=Files.createTempDirectory("p2wlan-display-test");
        try {
            var file=dir.resolve("config/display.json");var store=new DisplayPreferencesStore(file);
            check(store.load().preferences().equals(DisplayPreferences.defaults()));
            check(store.load().reason()==DisplayPreferencesStore.Reason.OK);
            for (var level:DisplayPreferences.Detail.values()) for (var corner:DisplayPreferences.Corner.values()) {
                var settings=new DisplayPreferences(true,level,corner);
                check(store.save(settings)==DisplayPreferencesStore.Reason.OK);
                check(store.load().preferences().equals(settings));
                try (var files=Files.list(file.getParent())) { check(files.count()==1); }
            }
            for (String text:List.of("{\"schema\":1,\"schema\":1}","{}","[]","null","x","{\"schema\":2}"," ".repeat(4097),
                    "{\"schema\":1,\"hud_visible\":false,\"detail\":\"BASIC\",\"corner\":\"TOP_LEFT\",\"token\":\"not-saved\"}",
                    "{\"schema\":1,\"hud_visible\":\"false\",\"detail\":\"BASIC\",\"corner\":\"TOP_LEFT\"}",
                    "{\"schema\":1,\"hud_visible\":false,\"detail\":\"BAD\",\"corner\":\"TOP_LEFT\"}")) {
                Files.writeString(file,text);check(store.load().reason()==DisplayPreferencesStore.Reason.DISPLAY_CONFIG_INVALID);
                check(store.load().preferences().equals(DisplayPreferences.defaults()));check(Files.readString(file).equals(text));
            }
            var other=dir.resolve("other");Files.writeString(other,"unrelated");Files.delete(file);Files.createSymbolicLink(file,other);
            check(store.load().reason()==DisplayPreferencesStore.Reason.DISPLAY_PATH_UNSAFE);
            check(store.save(DisplayPreferences.defaults())==DisplayPreferencesStore.Reason.DISPLAY_PATH_UNSAFE);
            check(Files.readString(other).equals("unrelated"));Files.delete(file);
            Files.delete(file.getParent());Files.createSymbolicLink(file.getParent(),dir);
            check(store.load().reason()==DisplayPreferencesStore.Reason.DISPLAY_PATH_UNSAFE);
            check(store.save(DisplayPreferences.defaults())==DisplayPreferencesStore.Reason.DISPLAY_PATH_UNSAFE);
            Files.delete(file.getParent());Files.delete(other);
            Files.writeString(file.getParent(),"not-a-directory");
            check(store.save(DisplayPreferences.defaults())==DisplayPreferencesStore.Reason.DISPLAY_SAVE_FAILED);
        } finally { try(var files=Files.walk(dir)) { for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
    private static void layouts() {
        for (int width:List.of(320,427,640,1280)) for (int height:List.of(240,360,720)) {
            var anchor=new MenuLogoLayout.Rect(width/2-100,height/4+70,200,20);
            var placement=MenuLogoLayout.beside(anchor,List.of(),width,height).orElseThrow();
            check(placement.icon().width()==20 && placement.icon().height()==20);
            check(!placement.icon().overlaps(anchor));check(placement.icon().y()==anchor.y());
            var row=List.of(new MenuLogoLayout.Rect(width/2-154,height-28,100,20),new MenuLogoLayout.Rect(width/2-50,height-28,100,20));
            var back=new MenuLogoLayout.Rect(width/2+54,height-28,100,20);
            var multiplayer=MenuLogoLayout.beside(back,row,width,height).orElseThrow();
            check(row.stream().noneMatch(multiplayer.icon()::overlaps));
            check(!new MenuLogoLayout.Rect(back.x(),back.y(),multiplayer.anchorWidth(),20).overlaps(multiplayer.icon()));
            var panel=ScreenLayout.of(width,height);check(panel.inBody(panel.row(3),24));
            check(panel.row(3)+28<=panel.footerY());
        }
        check(MenuLogoLayout.beside(new MenuLogoLayout.Rect(4,4,20,20),List.of(new MenuLogoLayout.Rect(0,0,320,240)),320,240).isEmpty());
    }
    private static void formatting() throws Exception {
        var runtime=new RuntimeView("a".repeat(32),"b".repeat(32));
        var peer=new PeerTelemetry(new AgentReply(runtime.owner(),1,new com.google.gson.JsonObject()),runtime,"peer","1","2",true,"direct","Minecraft","10.77.0.2",17L,2L,0L,2048L,4096L,1168,LatencyStatistics.parse(population()),1);
        check(TelemetryPresentation.hud(DisplayPreferences.defaults(),peer,2).isEmpty());
        for (var level:DisplayPreferences.Detail.values()) {
            var settings=new DisplayPreferences(true,level,DisplayPreferences.Corner.TOP_LEFT);
            var lines=TelemetryPresentation.hud(settings,peer,2);check(lines.size()==switch(level){case BASIC->1;case DETAILED->3;case ADVANCED->6;});
            check(lines.get(0).contains("17 ms"));
            var unknown=TelemetryPresentation.hud(settings,null,2);check(unknown.get(0).contains("待测量"));
            var stale=TelemetryPresentation.hud(settings,peer,PeerTelemetry.FRESH_NANOS+2);check(stale.get(0).contains("待更新"));
            if (level!=DisplayPreferences.Detail.BASIC) {check(lines.get(1).contains("50.5"));check(stale.get(1).contains("— / — / —"));}
            if (level==DisplayPreferences.Detail.ADVANCED) { check(lines.get(3).contains("95 / 99"));check(lines.get(4).contains("1168 B")); }
        }
        check(peer.statistics().samples()==100); // repeated formatting cannot create measurements
    }
    public static void main(String[] args) throws Exception {
        statistics();preferences();layouts();formatting();System.out.println("Display settings checks: "+checks);
    }
}
