package dev.xyat.realmcontrolvalidation;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.locale.Language;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*** Opens every RealmControl screen and captures it, in English and Chinese at two window sizes plus a long-text pass. Only child-editor drafts are applied; server settings are never saved. */
public final class GuiCaptureValidation {
    private static final Logger LOG=LoggerFactory.getLogger(GuiCaptureValidation.class);
    private static final String ROOT=System.getProperty("realmcontrol.guiValidation.output","D:/IDEAWork/RealmControl/.gradle/gui-capture/");
    private static final boolean FULL_HD_ONLY=Boolean.getBoolean("realmcontrol.guiValidation.fullHdOnly");
    private static final String[] NAMES={"ore-banned","ore-merge","weighted-merge","worldgen","structure-rule","biome-control","biome-rule","beacon-radii","beacon-spawn-rules"};
    // The page each case waits for before it captures; child pages open from their parent once it is showing.
    private static final String[] PAGES={"OreBannedPage","OreMergePage","WeightedBlockMergePage","WorldGenPage","StructureRuleEditPage","BiomeControlPage","BiomeRuleEditPage","BeaconRadiusEditor","BeaconSpawnRuleEditor"};
    private static final long OPEN_TIMEOUT=20000;
    private static boolean installed,started,screenshot,finished,originalFullscreen,childOpened,previewPrepared;
    private static final BitSet visitedPages = new BitSet();
    private static String originalLanguage;
    private static int originalScale,originalWidth,originalHeight,phase=-1,page=-1,captures,failures;
    private static long due,deadline;
    private static CompletableFuture<Void> reload;
    private static Language stressOriginal;
    private static final StringBuilder failureDetails = new StringBuilder();

    public static void install() { if(installed)return;installed=true;KineticClientEvents.onTick(KineticClientEvents.TickPhase.END,GuiCaptureValidation::tick); }
    private static void tick() {
        if(finished)return;
        try {
            var mc=Minecraft.getInstance();
            if(!started) {
                if(mc.player==null || mc.level==null || mc.getSingleplayerServer()==null)return;
                started=true;originalLanguage=mc.getLanguageManager().getSelected();originalScale=mc.options.guiScale().get();
                originalWidth=mc.getWindow().getWidth();originalHeight=mc.getWindow().getHeight();originalFullscreen=mc.getWindow().isFullscreen();
                Files.deleteIfExists(Path.of(ROOT,"result.txt"));
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
                    prepareBeaconPreview();
                    prepareStructurePreview();
                    previewPrepared=true;
                    due=now+1000;
                    return;
                }
                // The structure panel waits until the village sample has arrived and its mesh is built, then for the
                // row thumbnails; the rules page waits for its own preview.
                if(page==3 && (!structurePreviewShown(now) || !thumbnailsShown(now)))return;
                if(page==4 && !rulePreviewShown(now))return;
                if(now>=due) { capture("start");screenshot=true;due=now+(phase==4?3400:550); }
                return;
            }
            if(now>=due) {
                if(phase==4)capture("scroll");
                nextPage();
            }
        } catch(Throwable error) {
            recordFailure("REALM_GUI_FAIL phase="+phase+" page="+page+" case="+currentCase(),error);
            try { nextPage(); } catch(Throwable next) {
                recordFailure("REALM_GUI_NEXT_PAGE_FAIL phase="+phase+" page="+page+" case="+currentCase(),next);
                finish();
            }
        }
    }
    private static String currentPage() {
        var current=KineticGui.currentPage();
        return current==null?String.valueOf(Minecraft.getInstance().screen):current.getClass().getSimpleName();
    }
    private static void nextPhase() {
        if(stressOriginal!=null){Language.inject(stressOriginal);stressOriginal=null;}
        phase++;page=-1;
        // One English 1080p round by default; list phases (0-4) in realmcontrol.guiValidation.phases to run more.
        String selectedPhases=System.getProperty("realmcontrol.guiValidation.phases","1");
        while(phase<5 && !Boolean.getBoolean("realmcontrol.guiValidation.fullHdOnly") && !List.of(selectedPhases.split(",")).contains(String.valueOf(phase)))phase++;
        // Opt-in 1080p pass: English and Chinese; keep the usual phases by default.
        if(phase>=(FULL_HD_ONLY?2:5)){finish();return;}
        var mc=Minecraft.getInstance();
        mc.setScreen(null);
        String lang=(FULL_HD_ONLY?phase==1:phase==2 || phase==3)?"zh_cn":"en_us";
        mc.getLanguageManager().setSelected(lang);
        mc.options.languageCode=lang;
        boolean fullHd=FULL_HD_ONLY || phase==1 || phase==3;
        int width=fullHd?1920:854,height=fullHd?1080:480;
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
            case 7 -> openBeaconRadii(ignored -> { throw new AssertionError("capture must not apply radius draft"); });
            case 8 -> openBeaconRules(ignored -> { throw new AssertionError("capture must not apply spawn-rule draft"); });
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
            // The plains village, whose preview the panel already sampled, so the rules page shows it at once.
            Object village=descriptors.get(0);
            for(Object descriptor:descriptors) {
                if(descriptor.toString().contains("minecraft:village_plains")) { village=descriptor;break; }
            }
            invoke(parent.getClass(),parent,"openStructureEditor",village);
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
            int iconX=field(target,"rightX").getInt(target)+7;
            int iconY=field(target,"rightY").getInt(target)+3;
            var tooltips=target.getClass().getDeclaredMethod("renderTooltips",int.class,int.class);
            tooltips.setAccessible(true);tooltips.invoke(target,iconX,iconY);
            Object overlays=((dev.xyat.kineticcore.internal.client.gui.screen.KineticScreenHost)Minecraft.getInstance().screen).kineticRuntime().overlays();
            Object tooltip=field(overlays,"tooltip").get(overlays);
            if(tooltip==null || !tooltip.getClass().getSimpleName().equals("ItemTooltip"))
                throw new AssertionError("Weighted source header icon must request the native item tooltip at its drawn position");
            LOG.info("REALM_GUI_WEIGHTED_HEADER_TOOLTIP_PASS x={} y={} native=true",iconX,iconY);
        }
    }
    private static long structurePreviewSince;
    private static void prepareStructurePreview() throws Exception {
        if(page!=3)return;
        structurePreviewSince=0;
        Object target=KineticGui.currentPage();
        var descriptors=(List<?>)field(target,"structureDescriptors").get(target);
        Object village=descriptors.stream().filter(d -> d.toString().contains("minecraft:village_plains")).findFirst()
                .orElseThrow(() -> new AssertionError("no plains village descriptor"));
        invoke(target.getClass(),target,"selectStructure",village);
    }
    /** True once the selected structure's preview has been shown for a moment; fails after twenty seconds. */
    private static boolean structurePreviewShown(long now) throws Exception {
        Object target=KineticGui.currentPage();
        Object result=field(target,"previewResult").get(target);
        if(result==null) {
            require(now<deadline,"the structure preview did not arrive within "+OPEN_TIMEOUT+" ms");
            return false;
        }
        if(structurePreviewSince==0) {
            structurePreviewSince=now;
            var snapshot=(dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot)result.getClass().getMethod("snapshot").invoke(result);
            require(snapshot.nonAirCount()>100,"the village sample has hardly any blocks: "+snapshot.nonAirCount());
            LOG.info("REALM_GUI_STRUCTURE_PREVIEW_PASS blocks={} containers={} entities={} size={}x{}x{}",snapshot.nonAirCount(),
                    snapshot.containerCount(),snapshot.entityCount(),snapshot.sizeX(),snapshot.sizeY(),snapshot.sizeZ());
        }
        // A moment for the mesh to build over a few frames.
        return now-structurePreviewSince>2500;
    }
    private static long thumbnailsSince;
    /** True once several rows show thumbnails, or after thirty seconds with at least one; fails with none. */
    private static boolean thumbnailsShown(long now) throws Exception {
        if(thumbnailsSince==0)thumbnailsSince=now;
        Object target=KineticGui.currentPage();
        var descriptors=(List<?>)field(target,"structureDescriptors").get(target);
        int shown=0,none=0;
        for(Object descriptor:descriptors) {
            String id=((dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor)descriptor).structureId();
            if(dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient.none(id))none++;
            else if(dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient.ready(id))shown++;
        }
        if(shown<6 && now-thumbnailsSince<30000)return false;
        require(shown>0,"no row thumbnail arrived within 30 s");
        LOG.info("REALM_GUI_STRUCTURE_THUMBNAILS_PASS shown={} none={} waited={}ms",shown,none,now-thumbnailsSince);
        thumbnailsSince=0;
        return true;
    }
    private static long rulePreviewSince;
    /** True once the rules page shows its structure's preview for a moment; fails after twenty seconds. */
    private static boolean rulePreviewShown(long now) throws Exception {
        Object target=KineticGui.currentPage();
        if(field(target,"previewResult").get(target)==null) {
            require(now<deadline,"the rules page preview did not arrive within "+OPEN_TIMEOUT+" ms");
            rulePreviewSince=0;
            return false;
        }
        if(rulePreviewSince==0) {
            rulePreviewSince=now;
            LOG.info("REALM_GUI_RULE_PREVIEW_PASS");
        }
        return now-rulePreviewSince>2500;
    }
    private static java.lang.reflect.Field field(Object target,String name) throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static final List<String> RADIUS_DRAFT=List.of("-1","0","1","16","9");
    private static final List<String> SPAWN_DRAFT=List.of("minecraft:zombie;AEX;opaque-tail","minecraft:skeleton;AE","minecraft:sheep;H",
            "minecraft:villager;BC","minecraft:pig;D","minecraft:cow;H","minecraft:creeper;A","minecraft:spider;AG",
            "minecraft:enderman;A","minecraft:slime;A","minecraft:witch;AG","minecraft:blaze;AE",
            "validation:missing_entity;Qz","legacy-unparsed-rule");
    private static void openBeaconRadii(java.util.function.Consumer<List<String>> apply) {
        dev.xyat.realmcontrol.beacon.config.BeaconRadiusEditor.open(
                dev.xyat.kineticcore.api.text.KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.radii"),RADIUS_DRAFT,apply);
    }
    private static void openBeaconRules(java.util.function.Consumer<List<String>> apply) {
        dev.xyat.realmcontrol.beacon.config.BeaconSpawnRuleEditor.open(
                dev.xyat.kineticcore.api.text.KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_rules"),SPAWN_DRAFT,apply);
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    private static void prepareBeaconPreview() throws Exception {
        if(page!=7 && page!=8)return;
        var configRadii=new ArrayList<>(dev.xyat.realmcontrol.beacon.config.BeaconConfig.beaconLevelRadii);
        var configRules=new ArrayList<>(dev.xyat.realmcontrol.beacon.config.BeaconConfig.beaconRulesRaw);
        var applied=new java.util.concurrent.atomic.AtomicReference<List<String>>();
        if(page==7) {
            openBeaconRadii(applied::set);
            Object target=KineticGui.currentPage();
            var fields=(dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField[])field(target,"radiusFields").get(target);
            require(fields.length==4 && fields[0].getIntValue()==-1 && fields[3].getIntValue()==16,"radius draft did not populate the four beacon levels");
            fields[1].setTextValue("17");
            invokeNoArgs(target,"applyAndClose");
            require(applied.get()==null && KineticGui.currentPage()==target,"invalid radius must not leave the child or apply its draft");
            fields[0].setIntValue(2);fields[1].setIntValue(0);
            invokeNoArgs(target,"applyAndClose");
            require(List.of("2","0","1","16","9").equals(applied.get()),"radius edits changed another level or dropped legacy tail values");
            applied.set(null);
            dev.xyat.realmcontrol.beacon.config.BeaconRadiusEditor.open(net.minecraft.network.chat.Component.literal("Short legacy radius draft"),List.of("2"),applied::set);
            target=KineticGui.currentPage();
            invokeNoArgs(target,"applyAndClose");
            require(List.of("2","2","2","2").equals(applied.get()),"missing radius levels must retain the last-level fallback");
            applied.set(null);
            dev.xyat.realmcontrol.beacon.config.BeaconRadiusEditor.open(net.minecraft.network.chat.Component.literal("Empty legacy radius draft"),List.of(),applied::set);
            target=KineticGui.currentPage();
            invokeNoArgs(target,"applyAndClose");
            require(List.of("-1","-1","-1","-1").equals(applied.get()),"empty radius list must retain disabled loading");
            applied.set(null);
            openBeaconRadii(applied::set);
            KineticGui.currentPage().close();
            require(applied.get()==null,"Back must discard the radius child draft");
            openBeaconRadii(ignored -> { throw new AssertionError("capture must not apply radius draft"); });
            target=KineticGui.currentPage();
            fields=(dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField[])field(target,"radiusFields").get(target);
            fields[2].setIntValue(3);
        } else {
            openBeaconRules(applied::set);
            Object target=KineticGui.currentPage();
            var grid=beaconGrid(target);
            var registered=dev.xyat.kineticcore.api.registry.KineticRegistries.entityTypes().ids().stream().map(Object::toString).toList();
            var visible=grid.items().stream().map(dev.xyat.kineticcore.api.client.gui.widget.list.KineticEntityGrid.Entry::entityId).toList();
            require(visible.containsAll(registered) && visible.contains("validation:missing_entity") && visible.contains("legacy-unparsed-rule"),
                    "the initial browser must show every registered mob and retain unavailable/malformed saved entries");
            require(grid.columns()==6 && grid.items().size()>36,"all-entity browser must retain standard cards and scroll beyond its first viewport");
            var zombie=beaconEntry(target,"minecraft:zombie");
            var skeleton=beaconEntry(target,"minecraft:skeleton");
            require(zombie.selected() && zombie.current() && zombie.error() && skeleton.selected() && !skeleton.error(),
                    "configured cards, unknown-code errors and the independent current-card state are wrong");
            require(beaconEntry(target,"validation:missing_entity").error() && beaconEntry(target,"legacy-unparsed-rule").error(),
                    "unavailable entity IDs and malformed stored rules must have red card state");
            String tooltip=zombie.tooltip().getString();
            require(tooltip.contains("minecraft:zombie") && tooltip.contains(dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.beacon.rules.code.e").getString()),
                    "mob tooltip lacks its ID or translated blocked reason");
            require(Minecraft.getInstance().screen.children().stream().filter(child -> child instanceof dev.xyat.kineticcore.api.client.gui.widget.KineticButton button && button.controlY()==4).count()==3,
                    "the header must contain only Back, Delete and Apply, without an Add Mobs button");
            ((dev.xyat.kineticcore.api.client.gui.widget.KineticTextField)field(target,"searchBox").get(target)).setTextValue("minecraft:chicken");
            grid=beaconGrid(target);
            require(grid.items().size()==1,"unconfigured mob must be directly reachable through Search");
            clickBeacon(grid.controlX()+20,grid.controlY()+20);
            ((dev.xyat.kineticcore.api.client.gui.widget.KineticTextField)field(target,"searchBox").get(target)).setTextValue("");
            var chicken=beaconEntry(target,"minecraft:chicken");
            require(!chicken.selected() && chicken.current() && !chicken.error() && beaconDraftLines(target).equals(SPAWN_DRAFT),
                    "selecting an unconfigured mob must change only the current card, not create a rule");
            require(chicken.tooltip().getString().contains(dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.beacon.rules.code.a").getString()),
                    "unconfigured mob tooltip must show the natural-spawn A default");
            grid=beaconGrid(target);grid.setScrollOffset(Integer.MAX_VALUE);
            require(grid.scrollOffset()>0,"all registered mobs must be reachable through the Core grid scrollbar");
            var search=(dev.xyat.kineticcore.api.client.gui.widget.KineticTextField)field(target,"searchBox").get(target);
            search.setTextValue("minecraft:chicken");
            require(beaconGrid(target).items().size()==1 && beaconEntry(target,"minecraft:chicken").current(),"ID search must filter the all-entity grid without losing its current mob");
            search.setTextValue("validation:no_matching_entity");
            require(beaconGrid(target).items().isEmpty() && beaconDraftLines(target).equals(SPAWN_DRAFT),"empty search results must not change stored rules");
            search.setTextValue("");
            require(beaconGrid(target).items().stream().map(dev.xyat.kineticcore.api.client.gui.widget.list.KineticEntityGrid.Entry::entityId).toList().containsAll(registered),"clearing Search must restore the complete registry list");
            invokeNoArgs(target,"applyAndClose");
            require(SPAWN_DRAFT.equals(applied.get()),"selecting, searching and applying without edits must preserve every saved line exactly");

            applied.set(null);openBeaconRules(applied::set);target=KineticGui.currentPage();
            selectBeacon(target,"minecraft:chicken");
            var toggle=target.getClass().getDeclaredMethod("setBlockedCode",char.class,boolean.class);toggle.setAccessible(true);
            toggle.invoke(target,'H',true);
            chicken=beaconEntry(target,"minecraft:chicken");
            require(chicken.selected() && chicken.current() && !chicken.error() && beaconDraftLines(target).contains("minecraft:chicken;AH"),
                    "the first real edit must create the new mob's rule, preserve default A and mark it green");
            selectBeacon(target,"minecraft:zombie");toggle.invoke(target,'A',false);
            tooltip=beaconEntry(target,"minecraft:zombie").tooltip().getString();
            require(!tooltip.contains(dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.beacon.rules.code.a").getString())
                    && tooltip.contains(dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.beacon.rules.code.e").getString()),"mob tooltip did not refresh after changing the blocked reasons");
            invokeNoArgs(target,"applyAndClose");
            var expected=new ArrayList<>(SPAWN_DRAFT);expected.set(0,"minecraft:zombie;EX;opaque-tail");expected.add("minecraft:chicken;AH");
            require(expected.equals(applied.get()),"editing rules erased unavailable IDs, unknown codes, opaque tails or malformed lines");

            applied.set(null);openBeaconRules(applied::set);target=KineticGui.currentPage();
            selectBeacon(target,"validation:missing_entity");invokeNoArgs(target,"removeSelected");invokeNoArgs(target,"applyAndClose");
            expected=new ArrayList<>(SPAWN_DRAFT);expected.remove("validation:missing_entity;Qz");
            require(expected.equals(applied.get()),"explicit removal must affect only the chosen entity and retain unrelated legacy data");
            applied.set(null);
            dev.xyat.realmcontrol.beacon.config.BeaconSpawnRuleEditor.open(net.minecraft.network.chat.Component.literal("Duplicate rule draft"),
                    List.of("minecraft:zombie;AX;one","minecraft:zombie;BE;two"),applied::set);
            target=KineticGui.currentPage();toggle.invoke(target,'B',false);invokeNoArgs(target,"applyAndClose");
            require(List.of("minecraft:zombie;AX;one","minecraft:zombie;E;two").equals(applied.get()),"editing the effective duplicate erased an earlier opaque rule");
            applied.set(null);
            dev.xyat.realmcontrol.beacon.config.BeaconSpawnRuleEditor.open(net.minecraft.network.chat.Component.literal("Cleared duplicate rule draft"),
                    List.of("minecraft:zombie;EX;one","minecraft:zombie;B"),applied::set);
            target=KineticGui.currentPage();toggle.invoke(target,'B',false);invokeNoArgs(target,"applyAndClose");
            require(List.of("minecraft:zombie;EX;one","minecraft:zombie;A").equals(applied.get()),"clearing the effective rule reactivated an earlier duplicate instead of the natural-spawn default");
            applied.set(null);openBeaconRules(applied::set);target=KineticGui.currentPage();
            selectBeacon(target,"minecraft:chicken");toggle.invoke(target,'H',true);
            KineticGui.currentPage().close();
            require(applied.get()==null,"Back must discard even a modified all-entity spawn-rule child draft");
            openBeaconRules(ignored -> { throw new AssertionError("capture must not apply spawn-rule draft"); });
            target=KineticGui.currentPage();selectBeacon(target,"minecraft:chicken");toggle.invoke(target,'H',true);selectBeacon(target,"minecraft:zombie");
            LOG.info("REALM_GUI_ALL_ENTITIES_PASS registry={} cards={} selectionDoesNotWrite=true greenRedCurrent=true losslessSaveCancel=true",registered.size(),beaconGrid(target).items().size());
        }
        require(configRadii.equals(dev.xyat.realmcontrol.beacon.config.BeaconConfig.beaconLevelRadii)
                && configRules.equals(dev.xyat.realmcontrol.beacon.config.BeaconConfig.beaconRulesRaw),"child editor changed live beacon settings before the parent saved");
        LOG.info("REALM_GUI_BEACON_DRAFT_PASS case={} liveSettingsUnchanged=true",NAMES[page]);
    }
    private static dev.xyat.kineticcore.api.client.gui.widget.list.KineticEntityGrid beaconGrid(Object target) throws Exception {
        return (dev.xyat.kineticcore.api.client.gui.widget.list.KineticEntityGrid)field(target,"grid").get(target);
    }
    private static void clickBeacon(double x,double y) {
        var screen=Minecraft.getInstance().screen;
        var canvas=((dev.xyat.kineticcore.internal.client.gui.screen.KineticScreenHost)screen).kineticRuntime().canvas();
        double sx=canvas.toScreenX(x),sy=canvas.toScreenY(y);
        //? if >=26.1 {
        /*var event=new net.minecraft.client.input.MouseButtonEvent(sx,sy,new net.minecraft.client.input.MouseButtonInfo(0,0));
        screen.mouseClicked(event,false);screen.mouseReleased(event);*/
        //?} else {
        screen.mouseClicked(sx,sy,0);screen.mouseReleased(sx,sy,0);
        //?}
    }
    private static dev.xyat.kineticcore.api.client.gui.widget.list.KineticEntityGrid.Entry beaconEntry(Object target,String id) throws Exception {
        return beaconGrid(target).items().stream().filter(entry -> entry.entityId().equals(id)).findFirst().orElseThrow();
    }
    private static void selectBeacon(Object target,String id) throws Exception {
        var items=beaconGrid(target).items();
        for(int index=0;index<items.size();index++)if(items.get(index).entityId().equals(id)){invoke(target.getClass(),target,"selectRule",index);return;}
        throw new AssertionError("Missing entity card: "+id);
    }
    @SuppressWarnings("unchecked")
    private static List<String> beaconDraftLines(Object target) throws Exception {
        Object draft=field(target,"draft").get(target);var lines=draft.getClass().getDeclaredMethod("lines");lines.setAccessible(true);
        return (List<String>)lines.invoke(draft);
    }
    private static void invokeNoArgs(Object target,String name) throws Exception {
        var method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);
    }
    private static void capture(String frame)throws Exception {
        var mc=Minecraft.getInstance();Path path=Path.of(ROOT,String.format("%d-%02d-%s-%s.png",phase,page,NAMES[page],frame));Files.createDirectories(path.getParent());
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(path);}
        captures++;LOG.info("REALM_GUI_CAPTURE phase={} case={} image={}x{}",phase,NAMES[page],mc.getWindow().getWidth(),mc.getWindow().getHeight());
    }
    private static String currentCase() {
        return page>=0 && page<NAMES.length?NAMES[page]:"none";
    }
    private static void recordFailure(String context,Throwable error) {
        failures++;
        LOG.error(context,error);
        var trace=new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        failureDetails.append(context).append(System.lineSeparator()).append(trace).append(System.lineSeparator());
    }
    private static void finish() {
        if(finished)return;
        finished=true;
        var mc=Minecraft.getInstance();
        boolean userSettingsRestored=true;
        try {
            if(stressOriginal!=null){Language.inject(stressOriginal);stressOriginal=null;}
            if(started) {
                mc.options.guiScale().set(originalScale);
                mc.getLanguageManager().setSelected(originalLanguage);mc.options.languageCode=originalLanguage;
                mc.setScreen(null);
                mc.getWindow().setWindowed(originalWidth,originalHeight);
                if(originalFullscreen && !mc.getWindow().isFullscreen())mc.getWindow().toggleFullScreen();
            }
        } catch(Throwable error) {
            userSettingsRestored=false;
            recordFailure("REALM_GUI_SETTINGS_RESTORE_FAIL",error);
        }
        try {
            Path output=Path.of(ROOT);
            Files.createDirectories(output);
            Files.writeString(output.resolve("result.txt"),(failures==0?"PASS":"FAIL")
                    +" captures="+captures+" failures="+failures+System.lineSeparator()
                    +"phase="+phase+" page="+page+" case="+currentCase()
                    +" userSettingsRestored="+userSettingsRestored+System.lineSeparator()+failureDetails);
        } catch(Exception error) {
            recordFailure("REALM_GUI_RESULT_WRITE_FAIL output="+ROOT,error);
        }
        LOG.info("REALM_GUI_{} pages={} captures={} failures={} userSettingsRestored={}",failures==0?"PASS":"FAIL",visitedPages.cardinality(),captures,failures,userSettingsRestored);
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
