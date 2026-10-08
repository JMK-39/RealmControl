package dev.xyat.realmcontrolvalidation;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.locale.Language;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*** Opens every RealmControl screen and captures it, in English and Chinese at two window sizes plus a long-text pass. Never clicks or saves. */
public final class GuiCaptureValidation {
    private static final Logger LOG=LoggerFactory.getLogger(GuiCaptureValidation.class);
    private static final String ROOT=System.getProperty("realmcontrol.guiValidation.output","D:/IDEAWork/RealmControl/.gradle/gui-capture/");
    private static final String[] NAMES={"ore-banned","ore-merge","weighted-merge","worldgen","structure-rule","biome-control","biome-rule"};
    // The page each case waits for before it captures; child pages open from their parent once it is showing.
    private static final String[] PAGES={"OreBannedPage","OreMergePage","WeightedBlockMergePage","WorldGenPage","StructureRuleEditPage","BiomeControlPage","BiomeRuleEditPage"};
    private static final long OPEN_TIMEOUT=20000;
    private static boolean installed,started,screenshot,finished,originalFullscreen,childOpened,previewPrepared;
    private static final BitSet visitedPages = new BitSet();
    private static String originalLanguage;
    private static int originalScale,originalWidth,originalHeight,phase=-1,page=-1,captures,failures;
    private static long due,deadline;
    private static CompletableFuture<Void> reload;
    private static Language stressOriginal;

    public static void install() { if(installed)return;installed=true;KineticClientEvents.onTick(KineticClientEvents.TickPhase.END,GuiCaptureValidation::tick); }
    private static void tick() {
        if(finished)return;
        try {
            var mc=Minecraft.getInstance();
            if(!started) {
                if(mc.player==null || mc.level==null || mc.getSingleplayerServer()==null)return;
                started=true;originalLanguage=mc.getLanguageManager().getSelected();originalScale=mc.options.guiScale().get();
                originalWidth=mc.getWindow().getWidth();originalHeight=mc.getWindow().getHeight();originalFullscreen=mc.getWindow().isFullscreen();
                mc.options.guiScale().set(0);
                if(originalFullscreen)mc.getWindow().toggleFullScreen();
                nextPhase();
                return;
            }
            if(reload!=null) {
                if(!reload.isDone() || mc.getOverlay()!=null)return;
                reload.join();reload=null;
                if(phase==4) {
                    stressOriginal=Language.getInstance();
                    Language.inject(new StressLanguage(stressOriginal));
                }
                nextPage();return;
            }
            long now=System.currentTimeMillis();
            if(!screenshot) {
                String current=currentPage();
                if(!PAGES[page].equals(current)) {
                    if(!childOpened && (page==4 && "WorldGenPage".equals(current) || page==6 && "BiomeControlPage".equals(current))) {
                        openChild(page);childOpened=true;
                    }
                    if(now>=deadline)throw new IllegalStateException("page "+PAGES[page]+" did not open; showing "+current);
                    due=now+1000;return;
                }
                if (!previewPrepared) {
                    prepareItemPreview();
                    previewPrepared=true;
                    due=now+1000;
                    return;
                }
                if(now>=due) { capture("start");screenshot=true;due=now+(phase==4?3400:550); }
                return;
            }
            if(now>=due) {
                if(phase==4)capture("scroll");
                nextPage();
            }
        } catch(Throwable error) {
            failures++;LOG.error("REALM_GUI_FAIL phase="+phase+" page="+page,error);
            try { nextPage(); } catch(Throwable next) { finish(); }
        }
    }
    private static String currentPage() {
        var current=KineticGui.currentPage();
        return current==null?String.valueOf(Minecraft.getInstance().screen):current.getClass().getSimpleName();
    }
    private static void nextPhase() {
        if(stressOriginal!=null){Language.inject(stressOriginal);stressOriginal=null;}
        phase++;page=-1;
        if(phase>=5){finish();return;}
        var mc=Minecraft.getInstance();
        mc.setScreen(null);
        String lang=phase==2 || phase==3?"zh_cn":"en_us";
        mc.getLanguageManager().setSelected(lang);
        mc.options.languageCode=lang;
        int width=phase==1 || phase==3?1920:854,height=phase==1 || phase==3?1080:480;
        mc.getWindow().setWindowed(width,height);mc.resizeDisplay();
        reload=mc.reloadResourcePacks();
        LOG.info("REALM_GUI_PHASE phase={} language={} requested={}x{} autoScale=true",phase,lang,width,height);
    }
    private static void nextPage() throws Exception {
        page++;
        String selectedPages=System.getProperty("realmcontrol.guiValidation.pages", "");
        while(page<NAMES.length && !selectedPages.isBlank() && !List.of(selectedPages.split(",")).contains(String.valueOf(page)))page++;
        if(page>=NAMES.length){nextPhase();return;}
        screenshot=false;childOpened=false;previewPrepared=false;
        visitedPages.set(page);
        long now=System.currentTimeMillis();due=now+1000;deadline=now+OPEN_TIMEOUT;
        Minecraft.getInstance().setScreen(null);
        openPage(page);
        LOG.info("REALM_GUI_OPEN phase={} case={}",phase,NAMES[page]);
    }
    private static void openPage(int index) throws Exception {
        var mc=Minecraft.getInstance();
        var server=mc.getSingleplayerServer();
        var player=server.getPlayerList().getPlayer(mc.player.getUUID());
        switch(index) {
            case 0 -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.openOreBannedGui();
            case 1 -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.openOreMergeGui();
            case 2 -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.openWeightedBlockMergeGui();
            // The editor takes the server's data, built as the in-game command builds it; the test player need not be an operator.
            case 3,4 -> {
                var packet=server.submit(() -> {
                    dev.xyat.realmcontrol.worldgen.config.WorldGenConfig.load();
                    List<String> structures=new ArrayList<>();
                    try { for(Object id:(List<?>)invoke(dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork.class,null,"getAllStructureIds",server))structures.add((String)id); }
                    catch(Exception error){throw new IllegalStateException(error);}
                    return new dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork.OpenWorldGenGuiPacket(
                            dev.xyat.realmcontrol.worldgen.config.WorldGenConfig.enableStructureBlocking,structures,
                            dev.xyat.realmcontrol.worldgen.config.StructureGenerationControl.getDescriptors(server));
                }).join();
                KineticGui.openChild(new dev.xyat.realmcontrol.worldgen.client.gui.WorldGenPage(packet));
            }
            // Building the biome editor's data has no operator check, so the server sends it as usual.
            default -> server.execute(() -> {
                try { invoke(dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork.class,null,"sendBiomeControlEditor",player); }
                catch(Exception error){throw new IllegalStateException(error);}
            });
        }
    }
    private static void openChild(int index) throws Exception {
        Object parent=KineticGui.currentPage();
        if(index==4) {
            var field=parent.getClass().getDeclaredField("structureDescriptors");field.setAccessible(true);
            List<?> descriptors=(List<?>)field.get(parent);
            if(descriptors.isEmpty())throw new IllegalStateException("no structure descriptors");
            invoke(parent.getClass(),parent,"openStructureEditor",descriptors.get(0));
        } else {
            invoke(parent.getClass(),parent,"openEditor",-1);
        }
    }
    private static Object invoke(Class<?> type,Object target,String name,Object arg)throws Exception {
        for(var method:type.getDeclaredMethods()) {
            if(!method.getName().equals(name) || method.getParameterCount()!=1)continue;
            method.setAccessible(true);
            return method.invoke(target,arg);
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    @SuppressWarnings("unchecked")
    private static void prepareItemPreview() throws Exception {
        if (!Boolean.getBoolean("realmcontrol.guiValidation.itemPreview")) return;
        Object target=KineticGui.currentPage();
        if (page==1) {
            var rules=(Map<String,List<String>>)field(target,"tempRules").get(target);
            rules.put("minecraft:stone",new ArrayList<>(List.of("minecraft:dirt","minecraft:oak_planks")));
            ((Set<String>)field(target,"expandedTargets").get(target)).add("minecraft:stone");
            field(target,"selectedTarget").set(target,"minecraft:stone");
            invokeNoArgs(target,"updateLeftEntries");
            invokeNoArgs(target,"updateRightPanel");
        } else if (page==2) {
            var rules=(Map<String,List<dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig.WeightedBlockTarget>>)field(target,"rules").get(target);
            rules.put("minecraft:stone",new ArrayList<>(List.of(
                    new dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig.WeightedBlockTarget("minecraft:dirt",1),
                    new dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig.WeightedBlockTarget("minecraft:oak_planks",3))));
            invokeNoArgs(target,"refreshLeft");
            invoke(target.getClass(),target,"editRule","minecraft:stone");
        }
    }
    private static java.lang.reflect.Field field(Object target,String name) throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void invokeNoArgs(Object target,String name) throws Exception {
        var method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);
    }
    private static void capture(String frame)throws Exception {
        var mc=Minecraft.getInstance();Path path=Path.of(ROOT,String.format("%d-%02d-%s-%s.png",phase,page,NAMES[page],frame));Files.createDirectories(path.getParent());
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(path);}
        captures++;LOG.info("REALM_GUI_CAPTURE phase={} case={} image={}x{}",phase,NAMES[page],mc.getWindow().getWidth(),mc.getWindow().getHeight());
    }
    private static void finish() {
        finished=true;
        var mc=Minecraft.getInstance();
        if(stressOriginal!=null){Language.inject(stressOriginal);stressOriginal=null;}
        mc.options.guiScale().set(originalScale);
        mc.getLanguageManager().setSelected(originalLanguage);mc.options.languageCode=originalLanguage;
        mc.setScreen(null);
        mc.getWindow().setWindowed(originalWidth,originalHeight);
        if(originalFullscreen && !mc.getWindow().isFullscreen())mc.getWindow().toggleFullScreen();
        LOG.info("REALM_GUI_{} pages={} captures={} failures={} userSettingsRestored=true",failures==0?"PASS":"FAIL",visitedPages.cardinality(),captures,failures);
        dev.xyat.kineticcore.api.runtime.KineticClientRuntime.stopClient();
    }
    private static final class StressLanguage extends Language {
        private final Language delegate;
        StressLanguage(Language delegate){this.delegate=delegate;}
        @Override public String getOrDefault(String key,String fallback) {
            String text=delegate.getOrDefault(key,fallback);
            return key.startsWith("gui.realmcontrol.")?text+" - deliberately extended translation to verify text stays inside its own region":text;
        }
        @Override public boolean has(String key){return delegate.has(key);}
        @Override public boolean isDefaultRightToLeft(){return delegate.isDefaultRightToLeft();}
        @Override public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text){return delegate.getVisualOrder(text);}
    }
}
