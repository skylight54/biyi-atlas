package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasMapData;
import com.biyi.atlas.map.AtlasMapImage;
import com.biyi.atlas.map.AtlasMarker;
import com.biyi.atlas.map.AtlasPalette;
import com.biyi.atlas.map.AtlasPlace;
import com.biyi.atlas.map.AtlasRegion;
import com.biyi.atlas.map.AtlasRoad;
import com.biyi.atlas.map.AtlasRoadNode;
import com.biyi.atlas.map.AtlasWaypoint;
import com.biyi.atlas.map.TerrainCategory;
import com.biyi.atlas.map.WaypointType;
import com.biyi.atlas.net.AtlasNetwork;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 手持地图右键打开的全屏羊皮纸地图。 */
public class AtlasScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation PAPER =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/paper_ink.png");
    private static final ResourceLocation ARROW =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/arrow.png");
    private static final ResourceLocation PIXEL =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/pixel.png");
    private static final int SOLID = 16;
    private static final String MARKER_TEXTURE_PREFIX = "textures/atlas/marker/";

    private static final int PAPER_SIZE = 256;
    private static final int MARGIN = 16;
    private static final int MAX_QUADS = 40000;

    private ResourceLocation dimension;
    private final List<ResourceLocation> dimensionOrder = new ArrayList<>();
    private int dimensionIndex;
    private AtlasWaypoint hoveredWaypoint;
    private final boolean authorMode;

    /** 是否作者模式（服务端权限变化时要据此把界面降回玩家模式）。 */
    public boolean isAuthorMode() {
        return authorMode;
    }
    private Tool tool = Tool.SELECT;
    private final List<double[]> pendingRoad = new ArrayList<>();
    private final List<double[]> pendingRegion = new ArrayList<>();
    private AtlasPlace hoveredPlace;
    /** 当前查看的深度层与魔杖编辑层：0 地表，-1 地下，+1 上层… */
    private int viewLayer;
    private int editLayer;

    // ---- 界面控件（右侧面板 + 作者调色板），不用快捷键也能操作 ----
    private static final int PANEL_WIDTH = 92;
    private static final int BUTTON_HEIGHT = 18;
    private static final ResourceLocation UI_FRAME =
            new ResourceLocation(BiyiAtlas.MODID, "textures/ui/frame.png");
    private static final ResourceLocation UI_BUTTON =
            new ResourceLocation(BiyiAtlas.MODID, "textures/ui/button.png");
    private static final ResourceLocation UI_BUTTON_HOVER =
            new ResourceLocation(BiyiAtlas.MODID, "textures/ui/button_hover.png");
    /** 战争迷雾（未解锁区域对玩家整块盖住）。 */
    private static final ResourceLocation FOG_TEXTURE =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/fog.png");

    private final List<AtlasButton> buttons = new ArrayList<>();
    private enum UiSection {WORKFLOW,TOOLS,REGIONS,WAYPOINT,INSPECTOR,VIEW,SETTINGS,AI,CHROME}
    private UiSection buildingSection=UiSection.TOOLS,drawerSection=UiSection.TOOLS;
    private boolean drawerOpen;
    private boolean mapDragAllowed;
    private int drawerScroll,drawerMaxScroll;
    private String lastUiSelection="";

    private String selectionKey(){
        if(selectedPlace!=null)return "p:"+selectedPlace.key();
        if(selectedRegion!=null)return "r:"+selectedRegion.id();
        if(selectedWaypoint!=null)return "w:"+selectedWaypoint.name();
        if(selectedNode!=null)return "n:"+selectedNode.id();
        if(selectedImage!=null)return "i:"+selectedImage.id();
        if(selectedRoad!=null)return "road:"+selectedRoad.key();
        return "";
    }
    private void openDrawer(UiSection section){
        drawerOpen=section!=drawerSection||!drawerOpen;drawerSection=section;drawerScroll=0;rebuildButtons();
    }
    private AtlasUiLayout.Frame uiFrame(){return AtlasUiLayout.frame(this.width,this.height,authorMode);}
    private boolean inDrawer(double x,double y){return drawerOpen&&uiFrame().drawer().contains(x,y);}
    private void arrangeUi(){
        buttons.removeIf(b->b.section==UiSection.CHROME);
        String selected=selectionKey();
        if(!selected.isEmpty()&&!selected.equals(lastUiSelection)){drawerOpen=true;drawerSection=UiSection.INSPECTOR;drawerScroll=0;}
        lastUiSelection=selected;
        var frame=uiFrame();var dock=frame.drawer();
        int contentTop=frame.contentTop();
        java.util.List<AtlasButton> shown=new ArrayList<>();
        for(var b:buttons){
            b.visible=false;
            boolean belongs=b.section==drawerSection||(drawerSection==UiSection.REGIONS&&b.section==UiSection.WORKFLOW);
            if(!belongs||!drawerOpen)continue;
            if(b.section==UiSection.REGIONS&&(b.label.startsWith("区域 ▼")||b.label.startsWith("区域 ▶")))continue;
            if(b.section==UiSection.WAYPOINT&&(b.label.startsWith("路标 ▼")||b.label.startsWith("路标 ▶")))continue;
            if(b.section==UiSection.SETTINGS&&b.sourceX==MARGIN&&b.sourceY==MARGIN)continue;
            shown.add(b);
        }
        shown.sort(java.util.Comparator.comparingInt((AtlasButton b)->b.section.ordinal())
                .thenComparingInt(b->b.section==UiSection.TOOLS ? (b.sourceW==74?b.sourceY:b.icon!=null?20000+b.sourceY:java.util.Set.of("选择","画道路","画区域","放传送点").contains(b.label)?10000+b.sourceY:30000+b.sourceY) : b.section==UiSection.WORKFLOW?switch(b.label){
                    case "绘制区域"->0;case "设地图范围"->1;case "一键加载区域"->2;
                    case "取消准备"->3;case "发布底图"->4;default->5;
                }:b.sourceY).thenComparingInt(b->b.sourceX));
        java.util.List<AtlasUiLayout.Size> sizes=new ArrayList<>();
        for(var b:shown){
            int width=b.icon!=null?24:Math.max(font.width(b.label)+12,b.sourceW);
            if(b.section==UiSection.REGIONS)width=dock.width()-20;
            if(b.section==UiSection.WAYPOINT&&b.sourceW>100)width=dock.width()-48;
            sizes.add(new AtlasUiLayout.Size(width,Math.max(20,b.sourceH)));
        }
        var positions=AtlasUiLayout.flow(dock.width()-20,sizes);
        int total=AtlasUiLayout.contentHeight(positions);
        drawerMaxScroll=Math.max(0,total-(frame.contentBottom()-contentTop));
        drawerScroll=Math.max(0,Math.min(drawerScroll,drawerMaxScroll));
        for(int i=0;i<shown.size();i++){
            var b=shown.get(i);var r=positions.get(i);
            b.x=dock.x()+10+r.x();b.y=contentTop+r.y()-drawerScroll;b.w=r.width();b.h=r.height();
            b.visible=b.y>=contentTop&&b.y+b.h<=frame.contentBottom();
        }
        buildingSection=UiSection.CHROME;
        var nav=new ArrayList<UiSection>();
        if(authorMode)nav.add(UiSection.TOOLS);
        nav.add(UiSection.REGIONS);nav.add(UiSection.WAYPOINT);nav.add(UiSection.INSPECTOR);nav.add(UiSection.VIEW);
        if(authorMode){nav.add(UiSection.SETTINGS);nav.add(UiSection.AI);}
        java.util.List<AtlasUiLayout.Size> navSizes=new ArrayList<>();
        for(int i=0;i<nav.size()+2;i++)navSizes.add(new AtlasUiLayout.Size(42,18));
        var navRects=AtlasUiLayout.flow(this.width-32,navSizes);
        for(int i=0;i<nav.size();i++){
            var section=nav.get(i);var r=navRects.get(i);
            var button=new AtlasButton(16+r.x(),34+r.y(),r.width(),r.height(),sectionName(section),null,()->openDrawer(section));
            button.selected=drawerOpen&&drawerSection==section;buttons.add(button);
        }
        var locate=navRects.get(nav.size());
        buttons.add(new AtlasButton(16+locate.x(),34+locate.y(),42,18,"定位",null,this::centerOnPlayer));
        var close=navRects.get(nav.size()+1);
        buttons.add(new AtlasButton(16+close.x(),34+close.y(),42,18,"关闭",null,this::onClose));
        if(drawerOpen)buttons.add(new AtlasButton(dock.x()+dock.width()-38,dock.y()+7,30,18,"收起",null,()->{drawerOpen=false;rebuildButtons();}));
    }
    private String sectionName(UiSection section){return switch(section){
        case TOOLS->"工具";case REGIONS->"区域";case WAYPOINT->"路标";
        case INSPECTOR->authorMode?"属性":"详情";case VIEW->"视图";case SETTINGS->"设置";case AI->"MCP";default->"操作";
    };}
    public static void showForAi(boolean author,String panel){
        var screen=new AtlasScreen(author);
        Minecraft.getInstance().setScreen(screen);
        screen.drawerSection=switch(panel){case "details"->UiSection.INSPECTOR;case "mcp"->UiSection.AI;case "regions"->UiSection.REGIONS;default->UiSection.VIEW;};
        if(screen.drawerSection==UiSection.INSPECTOR){screen.selectedRegion=AtlasMapSession.active();screen.lastUiSelection=screen.selectionKey();}
        screen.drawerOpen=!panel.equals("none");screen.rebuildButtons();
    }
    /** 作者模式：调色板里选中的"画笔"图标（点击地图就放这个）。 */
    private String brushIcon = "town";
    /** 面板开关 */
    private boolean paletteOpen = true;

    // 选中态
    private AtlasPlace selectedPlace;
    private AtlasRoad selectedRoad;
    private AtlasRegion selectedRegion;
    private AtlasWaypoint selectedWaypoint;
    /** 选中的道路节点。 */
    private AtlasRoadNode selectedNode;
    /** 选中的"地点俯视图"（大型建筑的手绘俯视图元素）。 */
    private AtlasMapImage selectedImage;
    /** 生成俯视图的范围（方块半径），点按钮循环切换。 */
    private int captureRadius = 128;
    /** 地图上框选的两个角（世界坐标）。 */
    private double rectX1;
    private double rectZ1;
    private double rectX2;
    private double rectZ2;
    private boolean hasRect;
    /** 框选进度：0=无，1=已定第一角，2=已完成。 */
    private int rectStage;
    private double lastMouseX;
    private double lastMouseY;
    private double centerX;
    private double centerZ;
    private double pixelsPerBlock;
    private AtlasRegion revealRegion;
    private long revealOpened,revealDissolve=-1;
    private double revealFromX,revealFromZ,revealFromZoom;
    public boolean revealing(){return revealRegion!=null;}
    public String revealStatus(){return revealRegion==null?"无演出":revealRegion.id()+" · "+(revealDissolve<0?"聚焦/等待底图":String.format("散雾 %.0f%%",revealProgress()*100));}
    public static void openReveal(AtlasRegion region){
        var mc=Minecraft.getInstance();var screen=new AtlasScreen(false);mc.setScreen(screen);
        screen.dimension=region.dimension();screen.viewLayer=region.layer();screen.drawerOpen=false;
        AtlasRegion area=screen.outermostMapArea(region);
        if(area!=null&&area.mapArea())AtlasMapSession.select(area);
        screen.viewArea=area;screen.revealRegion=region;screen.revealOpened=net.minecraft.Util.getMillis();
        double[] b=region.bounds();double fit=screen.revealZoom(region);
        screen.revealFromX=(b[0]+b[2])/2+(b[2]-b[0])*.07;screen.revealFromZ=(b[1]+b[3])/2;
        screen.revealFromZoom=fit*.78;screen.centerX=screen.revealFromX;screen.centerZ=screen.revealFromZ;screen.pixelsPerBlock=screen.revealFromZoom;
        AtlasFogRenderer.ready(region);screen.rebuildButtons();
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,.75f,.45f));
    }
    private double revealZoom(AtlasRegion region){
        double[] b=region.bounds();return Math.max(.003,Math.min(4,Math.min(Math.max(80,width-100)/Math.max(1,b[2]-b[0]),Math.max(70,height-150)/Math.max(1,b[3]-b[1]))));
    }
    private double revealProgress(){return revealDissolve<0?0:Math.min(1,(net.minecraft.Util.getMillis()-revealDissolve)/2400.0);}
    private void updateReveal(){
        if(revealRegion==null)return;
        if(AtlasContent.get().regionById(revealRegion.id())==null||!AtlasVisibility.regionVisible(revealRegion.id())){revealRegion=null;return;}
        long now=net.minecraft.Util.getMillis();double[] b=revealRegion.bounds();double fit=revealZoom(revealRegion);
        double ease=com.biyi.atlas.map.FogRaster.smooth((now-revealOpened)/1000.0);
        centerX=revealFromX+((b[0]+b[2])/2-revealFromX)*ease;
        centerZ=revealFromZ+((b[1]+b[3])/2-12/fit-revealFromZ)*ease;
        pixelsPerBlock=revealFromZoom+(fit-revealFromZoom)*ease;
        boolean ready=AtlasFogRenderer.ready(revealRegion)&&!AtlasMapSession.status().startsWith("正在请求");
        if(revealDissolve<0&&now-revealOpened>950&&(ready||now-revealOpened>8000))revealDissolve=now;
        if(revealDissolve>=0&&now-revealDissolve>4000)revealRegion=null;
    }
    private void skipReveal(){revealRegion=null;rebuildButtons();}
    private void renderRevealOverlay(GuiGraphics g){
        if(revealRegion==null)return;
        double p=revealProgress();double age=revealDissolve<0?0:(net.minecraft.Util.getMillis()-revealDissolve)/1000.0;
        double title=com.biyi.atlas.map.FogRaster.smooth((age-.6)/.7)*(1-com.biyi.atlas.map.FogRaster.smooth((age-3.1)/.8));
        if(p>0&&p<.9){
            double[] b=revealRegion.bounds(),origin=com.biyi.atlas.map.FogRaster.origin(revealRegion.outline(),b);double radius=0;
            for(var v:revealRegion.outline())radius=Math.max(radius,Math.hypot(v[0]-origin[0],v[1]-origin[1]));
            double r=radius*p*1.3;
            for(int i=0;i<72;i++){
                double a=i*Math.PI/36,c=(i+1)*Math.PI/36,x=origin[0]+Math.cos(a)*r,z=origin[1]+Math.sin(a)*r,xx=origin[0]+Math.cos(c)*r,zz=origin[1]+Math.sin(c)*r;
                if(revealRegion.contains(x,z)&&revealRegion.contains(xx,zz))drawPolyline(g,List.of(new double[]{x,z},new double[]{xx,zz}),1,0x68DAC59A,false);
            }
        }
        int y=Math.max(40,height-92);
        if(title>.01){
            int alpha=(int)(255*title),ink=(alpha<<24)|0x344B49,gold=(alpha<<24)|0xAB8A53;
            g.drawCenteredString(font,"山 河 已 明",width/2,y,ink);
            blitRect(g,width/2-60,y+16,width/2+60,y+17,gold);
            String name=font.plainSubstrByWidth(revealRegion.name(),Math.max(60,width-90));
            g.drawCenteredString(font,name,width/2,y+25,ink);
        }
        String hint=revealDissolve<0?"正在展开区域 · 按空格跳过":"区域已解锁 · 按空格跳过";
        g.drawCenteredString(font,hint,width/2,height-25,0xFF74817B);
    }
    private int drawnChunks;
    private int exploredChunks;
    private boolean loggedFirstTile;

    public AtlasScreen() {
        this(false);
    }

    public AtlasScreen(boolean authorMode) {
        super(Component.literal(AtlasStyle.INSTANCE.title()));
        this.authorMode = authorMode;
        this.drawerOpen=authorMode;
        Minecraft minecraft = Minecraft.getInstance();
        this.dimension = minecraft.level != null
                ? minecraft.level.dimension().location()
                : Level.OVERWORLD.location();
        this.pixelsPerBlock = AtlasStyle.INSTANCE.zoomDefault();
        centerOnPlayer();
        rebuildDimensionOrder();
    }

    /** 可在界面上切换的维度：玩家当前维度 + 已有探索数据的维度。 */
    private void rebuildDimensionOrder() {
        dimensionOrder.clear();
        dimensionOrder.add(dimension);
        for (ResourceLocation other : AtlasStorage.loadedDimensions()) {
            if (!dimensionOrder.contains(other)) {
                dimensionOrder.add(other);
            }
        }
        dimensionIndex = dimensionOrder.indexOf(dimension);
        if (dimensionIndex < 0) {
            dimensionIndex = 0;
        }
    }

    private void cycleDimension(int step) {
        if (dimensionOrder.size() <= 1) {
            return;
        }
        dimensionIndex = Math.floorMod(dimensionIndex + step, dimensionOrder.size());
        dimension = dimensionOrder.get(dimensionIndex);
        hoveredWaypoint = null;
        hoveredPlace = null;
        // 换维度后必须清掉选中项并重建按钮：否则回调里还拿着旧维度的对象
        clearSelection();
        rebuildButtons();
    }

    public static void open() {
        openScreen(false);
    }

    public static void openAuthor() {
        openScreen(true);
    }

    private static void openScreen(boolean author) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (author && !minecraft.player.isCreative() && !minecraft.player.hasPermissions(2)) {
            minecraft.player.displayClientMessage(
                    Component.literal("[避役图志] 地图魔杖只有创造模式或管理员可以使用"), false);
            return;
        }
        minecraft.setScreen(new AtlasScreen(author));
    }

    private void centerOnPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            refreshViewArea();
            setCenter(minecraft.player.getX(), minecraft.player.getZ());
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 自己铺纸，不用原版泥土背景
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateReveal();
        blitBudget = authorMode ? BLIT_BUDGET_PER_FRAME * 4 : BLIT_BUDGET_PER_FRAME;   // 每帧重置渲染预算（作者模式放宽）
        renderPaper(graphics);

        int left = MARGIN;
        int top = MARGIN;
        int right = this.width - MARGIN;
        int bottom = this.height - MARGIN;

        // 绘制顺序很关键：GUI 不写深度，同一 z 上后画的会盖住先画的。
        // 之前的 bug 就是"整屏纸色统一层"画在地图内容之后，把地块/路标全盖没了
        // （玩家箭头因为 translate(...,200) 抬高了 z 才幸存）。
        // 所以：底纸 → 整屏纸色/边框 → 地图内容 → 文字。
        renderSepia(graphics, left, top, right, bottom);
        renderFrame(graphics, left, top, right, bottom);

        // 地图内容再抬高 1 个 z，确保不会被任何覆盖层压住。
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, 1.0F);
        if (DEBUG_SWATCHES) {
            // 与地块完全同一层、同一路径的红色方块：它若出现而地块不出现，问题就在数据/坐标
            blitRect(graphics, 60, 60, 92, 92, 0xFFA8322D);
            renderDebugSwatches(graphics, 180);
        }
        // 同一"层"内的绘制顺序（从下往上）：
        //   0 旅行地图底片（作者参考）  1 作者手绘地图图层  2 区域  3 道路  4 标记/地点/路标  5 玩家箭头
        renderMapContent(graphics);
        pose.popPose();

        if (DEBUG_SWATCHES) {
            renderDebugSwatches(graphics, 330);
        }
        if (authorMode && !MINIMAL_UI) {
            renderAuthorToolbar(graphics, left, top);
        }
        // 面板与文字抬到 z=2：地图内容在 z=1，避免地图图层盖住界面
        PoseStack overlayPose = graphics.pose();
        overlayPose.pushPose();
        overlayPose.translate(0.0F, 0.0F, 2.0F);
        renderVertexHandles(graphics);
        if(revealing())renderRevealOverlay(graphics);
        else {renderTexts(graphics,left,top,right,bottom);renderPanels(graphics);}
        overlayPose.popPose();

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private final List<int[]> mapLabels = new ArrayList<>();

    private boolean reserveMapLabel(int x, int y, int w, int h) {
        if (x < MARGIN || y < MARGIN || x + w > this.width - MARGIN || y + h > this.height - MARGIN) return false;
        for (int[] rect : mapLabels) {
            if (x < rect[2] && x + w > rect[0] && y < rect[3] && y + h > rect[1]) return false;
        }
        mapLabels.add(new int[]{x - 2, y - 2, x + w + 2, y + h + 2});
        return true;
    }

    /** 无界面装饰的共用内容层；手持与全屏执行相同可见性和导航规则。 */
    private void renderMapContent(GuiGraphics graphics) {
        mapLabels.clear();
        if(!authorMode)viewArea=AtlasMapSession.active();
        if(revealRegion!=null&&viewArea==null)viewArea=revealRegion;
        AtlasMapSession.rendering(authorMode,viewArea);
        if (viewLayer == 0 && dimension.equals(Level.OVERWORLD.location())) {
            AtlasTerrainTiles.render(graphics, authorMode?AtlasStorage.get(dimension).terrain():AtlasMapSession.terrain(),
                    this.width, this.height, centerX, centerZ, pixelsPerBlock,
                    (x, z) -> (authorMode||AtlasMapSession.inside(x,z))&&elementVisible(AtlasVisibility.Kind.MARKER, null, x, z));
        }
        var artArea=AtlasMapSession.active();
        if(AtlasPresentation.usable(artArea)&&artArea.dimension().equals(dimension)&&artArea.layer()==viewLayer){
            double[] b=artArea.bounds();
            AtlasPresentationRenderer.draw(graphics,AtlasPresentation.texture(),AtlasPresentation.registration(),b,artArea.outline(),width,height,centerX,centerZ,pixelsPerBlock);
        }
        renderJourneyUnderlay(graphics);
        // 映像图已停用；旧存档字段仅作兼容保留。
        renderRegions(graphics);
        renderRoads(graphics);
        renderBrushPreview(graphics);
        renderRoute(graphics);
        renderPendingRect(graphics);
        renderMarkers(graphics);
        renderPlaces(graphics);
        renderWaypoints(graphics);
        renderPlayerArrow(graphics);
        // Fog is composited after all map elements so the reveal also unveils roads/labels.
        if(!authorMode){
            if(AtlasSettings.fogEnabled())for(var region:AtlasContent.get().regionsAt(dimension,viewLayer)){
                if(!AtlasVisibility.regionVisible(region.id()))drawFogStamps(graphics,region);
            }
            if(revealRegion!=null)drawFogStamps(graphics,revealRegion);
        }
        if(!authorMode)renderAreaMask(graphics);
    }

    private void renderAreaMask(GuiGraphics g){
        if(viewArea!=null&&AtlasFogRenderer.outside(g,viewArea,centerX,centerZ,pixelsPerBlock,width,height))return;
        for(int y=MARGIN;y<this.height-MARGIN;y+=2){
            double wz=worldZOf(y+1);
            List<Double> cuts=new ArrayList<>();
            if(viewArea!=null){var p=viewArea.outline();
                for(int i=0,j=p.size()-1;i<p.size();j=i++){
                    double[] a=p.get(j),b=p.get(i);
                    if((a[1]>wz)!=(b[1]>wz))cuts.add(screenX(a[0]+(b[0]-a[0])*(wz-a[1])/(b[1]-a[1])));
                }
            }
            java.util.Collections.sort(cuts);
            int from=MARGIN;
            for(int i=0;i+1<cuts.size();i+=2){
                int a=Math.max(MARGIN,Math.min(this.width-MARGIN,(int)Math.ceil(cuts.get(i))));
                int b=Math.max(MARGIN,Math.min(this.width-MARGIN,(int)Math.floor(cuts.get(i+1))));
                AtlasFogRenderer.cloudRect(g,centerX,centerZ,pixelsPerBlock,width,height,from,y,a,Math.min(y+2,height-MARGIN));from=Math.max(from,b);
            }
            AtlasFogRenderer.cloudRect(g,centerX,centerZ,pixelsPerBlock,width,height,from,y,width-MARGIN,Math.min(y+2,height-MARGIN));
        }
        if(viewArea==null)g.drawCenteredString(font,"暂无已开放的地图区域",this.width/2,this.height/2,0xFFE0E4DF);
    }

    /** 保持一个离屏视图，不成为当前 Screen，也不创建编辑控件。 */
    void renderHeldMap(GuiGraphics graphics) {
        this.width = 256;
        this.height = 256;
        Minecraft client = Minecraft.getInstance();
        this.font = client.font;
        this.minecraft = client;
        if (client.player == null || client.level == null) {
            return;
        }
        dimension = client.level.dimension().location();
        centerX = client.player.getX();
        centerZ = client.player.getZ();
        pixelsPerBlock=AtlasSettings.heldZoom();
        var area=AtlasMapSession.active();
        if(area!=null&&!area.contains(centerX,centerZ)){
            double[] b=area.bounds();centerX=(b[0]+b[2])/2;centerZ=(b[1]+b[3])/2;
            pixelsPerBlock=Math.max(.01,Math.min(2,fitZoom(area)*AtlasSettings.heldZoom()/com.biyi.atlas.map.HeldMapView.DEFAULT));
        }
        blitBudget = BLIT_BUDGET_PER_FRAME;
        graphics.fill(12,12,244,244,0xFFB6995C);
        renderMapContent(graphics);
        graphics.flush();AtlasFogShader.configureBlend();
        graphics.blit(new ResourceLocation(BiyiAtlas.MODID,"textures/atlas/held_leather_frame.png"),0,0,256,256,0,0,1024,1024,1024,1024);
        graphics.drawCenteredString(font, Component.translatable("key.biyi_atlas.open")
                .append(" [").append(AtlasKeys.OPEN.getTranslatedKeyMessage()).append("]"),
                128, 242, 0xFF392A16);
    }

    private void renderPaper(GuiGraphics graphics) {
        int columns = (this.width + PAPER_SIZE - 1) / PAPER_SIZE;
        int rows = (this.height + PAPER_SIZE - 1) / PAPER_SIZE;
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row < rows; row++) {
                graphics.blit(PAPER, column * PAPER_SIZE, row * PAPER_SIZE, PAPER_SIZE, PAPER_SIZE,
                        0.0F, 0.0F, PAPER_SIZE, PAPER_SIZE, PAPER_SIZE, PAPER_SIZE);
            }
        }
    }

    /** 新纸面保留原生冷灰色，不叠加旧黄褐滤镜。 */
    private void renderSepia(GuiGraphics graphics, int left, int top, int right, int bottom) {
        AtlasUiTheme.rect(graphics, 0, 0, this.width, this.height, 0xB0DEE1DA);
    }

    private void renderFrame(GuiGraphics graphics, int left, int top, int right, int bottom) {
        AtlasUiTheme.rect(graphics, left, top, right - left, 1, 0x50636F6C);
        AtlasUiTheme.rect(graphics, left, bottom - 1, right - left, 1, 0x50636F6C);
    }
    private void renderChunks(GuiGraphics graphics) {
        AtlasMapData data = AtlasStorage.get(dimension);
        exploredChunks = data.size();
        drawnChunks = 0;
        if (data.isEmpty() && AtlasStyle.INSTANCE.revealed().isEmpty()) {
            return;
        }
        double halfWidthBlocks = this.width / 2.0D / pixelsPerBlock;
        double halfHeightBlocks = this.height / 2.0D / pixelsPerBlock;
        int minChunkX = Mth.floor(centerX - halfWidthBlocks) >> 4;
        int maxChunkX = Mth.floor(centerX + halfWidthBlocks) >> 4;
        int minChunkZ = Mth.floor(centerZ - halfHeightBlocks) >> 4;
        int maxChunkZ = Mth.floor(centerZ + halfHeightBlocks) >> 4;

        int size = Math.max(1, (int) Math.ceil(16.0D * pixelsPerBlock));
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                int category;
                int packed = 0;
                if (data.has(chunkX, chunkZ)) {
                    packed = data.get(chunkX, chunkZ);
                    category = categoryFor(data, AtlasMapData.colorId(packed), AtlasMapData.height(packed));
                } else {
                    // 玩家没探索过，但作者把这算作"已知区域"
                    if (AtlasStyle.INSTANCE.revealedColorAt(dimension, (chunkX << 4) + 8, (chunkZ << 4) + 8) == -1) {
                        continue;
                    }
                    category = TerrainCategory.OTHER;
                }
                // 按整块取整，避免非整数缩放下相邻瓦片出现 1px 缝隙
                int x1 = (int) Math.round(screenX(chunkX << 4));
                int y1 = (int) Math.round(screenY(chunkZ << 4));
                int x2 = (int) Math.round(screenX((chunkX + 1) << 4));
                int y2 = (int) Math.round(screenY((chunkZ + 1) << 4));
                int x = x1;
                int y = y1;
                int[] rect = clipToMap(x1, y1, Math.max(x1 + 1, x2), Math.max(y1 + 1, y2));
                if (rect == null) {
                    continue;
                }
                // 手绘地形瓦片：按地形类别选图集的一行，按区块坐标选变体，再按高度做明暗
                int height = packed == 0 ? 64 : AtlasMapData.height(packed);
                float shade = heightShade(height);
                int variant = TerrainTiles.variantAt(chunkX, chunkZ);
                graphics.setColor(shade, shade, shade, 1.0F);
                graphics.blit(TerrainTiles.ATLAS, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1],
                        (float) (variant * TerrainTiles.TILE_PX), (float) (category * TerrainTiles.TILE_PX),
                        TerrainTiles.TILE_PX, TerrainTiles.TILE_PX,
                        TerrainTiles.ATLAS_W, TerrainTiles.ATLAS_H);
                if (!loggedFirstTile) {
                    loggedFirstTile = true;
                    LOGGER.info("[BiyiAtlas] first terrain tile chunk=({},{}) screen=({},{}) size={} category={} height={}",
                            chunkX, chunkZ, x, y, size, category, height);
                }
                // 相邻地形不同 → 在交界侧叠一层墨线（手绘地图的边界感）
                if (drawnChunks < MAX_QUADS - 5) {
                    drawTerrainEdges(graphics, data, chunkX, chunkZ, category, rect);
                }
                if (++drawnChunks >= MAX_QUADS) {
                    graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                    return;
                }
            }
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderMarkers(GuiGraphics graphics) {
        for (AtlasMarker marker : AtlasStyle.INSTANCE.markers()) {
            if (!marker.dimension().equals(dimension)) {
                continue;
            }
            // 坐标级可见性：标记点没有 region 字段（X-22 的结构缺口），至少按坐标过滤
            if (!elementVisible(AtlasVisibility.Kind.MARKER, null, marker.x(), marker.z())) {
                continue;
            }
            int x = (int) Math.round(screenX(marker.x()));
            int y = (int) Math.round(screenY(marker.z()));
            if (!iconVisible(x, y)) {
                continue;
            }
            ResourceLocation texture = iconTexture(marker.icon());
            graphics.blit(texture, x - 8, y - 16, 16, 16, 0.0F, 0.0F, 32, 32, 32, 32);
        }
    }

    /** 图标名 → 贴图路径；找不到的图标退回内置 pin，避免每帧刷屏报错。 */
    private static final Map<String, ResourceLocation> ICON_CACHE = new HashMap<>();
    private static final ResourceLocation FALLBACK_ICON =
            new ResourceLocation(BiyiAtlas.MODID, MARKER_TEXTURE_PREFIX + "pin.png");

    private static ResourceLocation iconTexture(String icon) {
        // 自定义标记图标（作者导入的 PNG）直接走动态贴图路径
        if (CustomIcons.isCustom(icon)) {
            return CustomIcons.texture(icon);
        }
        return ICON_CACHE.computeIfAbsent(icon, key -> {
            ResourceLocation candidate =
                    new ResourceLocation(BiyiAtlas.MODID, MARKER_TEXTURE_PREFIX + key + ".png");
            try {
                if (Minecraft.getInstance().getResourceManager().getResource(candidate).isPresent()) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 资源管理器尚未就绪时按缺失处理
            }
            return FALLBACK_ICON;
        });
    }

    /** 临时诊断开关：四种画法各画一块，用来确认裁剪区内哪种画法真的出画面。
     *  2026-10-09 实测（工作端 100+ 模组 + Embeddium/Oculus）：enableScissor 区间内的绘制
     *  （含 blit）全部丢失，区间外全部正常 → 已改为手动裁剪，不再使用 enableScissor。 */
    private static final boolean DEBUG_SWATCHES = false;
    /** 极简界面：隐藏旧的左侧快捷键提示条（功能都在右侧/底部面板里）。 */
    private static final boolean MINIMAL_UI = true;
    private boolean loggedFirstWaypoint;

    /** 把矩形夹到地图区域内；完全在外返回 null。 */
    private static int[] clipToMap(int x1, int y1, int x2, int y2, int width, int height) {
        int left = Math.max(x1, MARGIN);
        int top = Math.max(y1, MARGIN);
        int right = Math.min(x2, width - MARGIN);
        int bottom = Math.min(y2, height - MARGIN);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new int[]{left, top, right, bottom};
    }

    private int[] clipToMap(int x1, int y1, int x2, int y2) {
        return clipToMap(x1, y1, x2, y2, this.width, this.height);
    }

    /** 四种画法各画一块，位置固定，方便一眼对比。 */
    private void renderDebugSwatches(GuiGraphics graphics, int y) {
        // A: 纯白贴图，不染色（应为白色）
        graphics.blit(PIXEL, 40, y, 32, 32, 0.0F, 0.0F, SOLID, SOLID, SOLID, SOLID);
        // B: 纯白贴图 + 红色染色
        graphics.setColor(1.0F, 0.0F, 0.0F, 1.0F);
        graphics.blit(PIXEL, 84, y, 32, 32, 0.0F, 0.0F, SOLID, SOLID, SOLID, SOLID);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        // C: fill 绿色
        graphics.fill(128, y, 160, y + 32, 0xFF00FF00);
        // D: arrow 贴图原样
        graphics.blit(ARROW, 172, y, 32, 32, 0.0F, 0.0F, 32, 32, 32, 32);
        // E: pin 贴图原样（路标用的就是它）
        graphics.blit(iconTexture("pin"), 216, y, 32, 32, 0.0F, 0.0F, 32, 32, 32, 32);
    }

    /** 裁剪区里 fill/fillGradient 不显色，所有色块都用 1x1 白贴图染色来画。 */
    private void blitRect(GuiGraphics graphics, int x1, int y1, int x2, int y2, int argb) {
        int[] rect = clipToMap(x1, y1, x2, y2);
        if (rect == null) {
            return;
        }
        graphics.setColor(((argb >> 16) & 0xFF) / 255.0F, ((argb >> 8) & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, ((argb >>> 24) & 0xFF) / 255.0F);
        graphics.blit(PIXEL, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1],
                0.0F, 0.0F, SOLID, SOLID, SOLID, SOLID);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 用墨色描边表示"悬停/选中"，不用大白块（用户反馈白色色块很突兀）。 */
    private void drawInkRing(GuiGraphics graphics, int x, int y, int w, int h, int argb) {
        blitRect(graphics, x, y, x + w, y + 1, argb);
        blitRect(graphics, x, y + h - 1, x + w, y + h, argb);
        blitRect(graphics, x, y, x + 1, y + h, argb);
        blitRect(graphics, x + w - 1, y, x + w, y + h, argb);
    }

    /** 图标是否离地图区域足够近，值得画。 */
    private boolean iconVisible(int x, int y) {
        return x >= MARGIN - 16 && x <= this.width - MARGIN + 16
                && y >= MARGIN - 24 && y <= this.height - MARGIN + 16;
    }

    /**
     * 该坐标是否落在"未解锁区域"里——落在里面的一切（地点、道路、路标）都对玩家隐藏，
     * 这样解锁前看不到区域内的地图信息，而不是只靠元素自己填的 region 字段。
     */
    private boolean hiddenByLockedRegion(double worldX, double worldZ) {
        // 作者始终能看到全部内容（否则没法编辑）；玩家侧走单一真源
        if (authorMode) {
            return false;
        }
        return AtlasVisibility.hiddenByLockedRegion(worldX, worldZ, dimension, viewLayer);
    }

    /** 元素级可见性（区域级 + 该类型的封锁设置 + 作者旁路）：所有渲染与命中都必须走它。 */
    private boolean elementVisible(AtlasVisibility.Kind kind, String regionId, double worldX, double worldZ) {
        if(!authorMode&&!AtlasMapSession.inside(worldX,worldZ))return false;
        return AtlasVisibility.visible(kind, regionId, worldX, worldZ, authorMode, dimension, viewLayer);
    }

    /** 某一段折线是否可见：按**该段中点**的区域归属判定（逐段口径，取代"整条按首点"）。 */
    private boolean segmentVisible(AtlasRoad road, int segment) {
        if (authorMode) {
            return true;
        }
        // A6 第②步收尾：服务端给了这条路的逐段掩码时以掩码为准（未授权段不下发也不该画）
        if (AtlasServerView.authoritative() && AtlasServerContent.hasMask(road.key())) {
            return AtlasServerContent.maskAllows(road.key(), segment);
        }
        AtlasRegionIndex.ensure(dimension, viewLayer);
        double[] mid = AtlasRegionIndex.midpointOf(road, segment);
        if (mid == null) {
            return false;
        }
        return elementVisible(AtlasVisibility.Kind.ROAD, AtlasRegionIndex.regionOf(road, segment), mid[0], mid[1]);
    }

    /** 整条路是否至少有一段可见。 */
    private boolean roadVisible(AtlasRoad road) {
        if (authorMode) {
            return true;
        }
        List<double[]> points = road.points();
        for (int segment = 0; segment + 1 < points.size(); segment++) {
            if (segmentVisible(road, segment)) {
                return true;
            }
        }
        return false;
    }


    // ============================================================ 笔刷（区域 / 道路）

    /** 绘制方式（笔刷）。 */
    private enum Brush {
        POLY("逐点"),
        FREEHAND("手绘"),
        LINE("直线"),
        CIRCLE("圆形"),
        RECT("矩形");

        final String label;

        Brush(String label) {
            this.label = label;
        }
    }

    private Brush brushForCurrentTool() {
        return tool == Tool.REGION ? regionBrush : tool == Tool.ROAD ? roadBrush : Brush.POLY;
    }

    /** 该笔刷是不是"按住拖拽成形"（而非逐点点击）。 */
    private boolean brushIsDrag() {
        Brush brush = brushForCurrentTool();
        if (tool == Tool.REGION) {
            return brush == Brush.CIRCLE || brush == Brush.RECT || brush == Brush.FREEHAND;
        }
        if (tool == Tool.ROAD) {
            return brush == Brush.LINE || brush == Brush.FREEHAND;
        }
        return false;
    }

    private void beginBrush(double worldX, double worldZ) {
        brushDragging = true;
        brushStartX = worldX;
        brushStartZ = worldZ;
        brushCurrentX = worldX;
        brushCurrentZ = worldZ;
        brushTrail.clear();
        brushTrail.add(new double[]{worldX, worldZ});
    }

    private void updateBrush(double worldX, double worldZ) {
        brushCurrentX = worldX;
        brushCurrentZ = worldZ;
        if (brushForCurrentTool() == Brush.FREEHAND && !brushTrail.isEmpty()) {
            double[] last = brushTrail.get(brushTrail.size() - 1);
            if (Math.hypot(worldX - last[0], worldZ - last[1]) >= 0.5D) {
                brushTrail.add(new double[]{worldX, worldZ});
            }
        }
    }

    /** 松手成形：复用 finishRegion / finishRoad 的校验（父区域判定、同层重叠拒绝）。 */
    private void finishBrush() {
        if (!brushDragging) {
            return;
        }
        brushDragging = false;
        Brush brush = brushForCurrentTool();
        if (tool == Tool.REGION) {
            List<double[]> outline = regionBrushOutline(brush);
            if (outline.size() < 3) {
                message("形状太小，已取消");
                return;
            }
            pendingRegion.clear();
            pendingRegion.addAll(outline);
            finishRegion();
        } else if (tool == Tool.ROAD) {
            List<double[]> points = roadBrushPoints(brush);
            if (points.size() < 2) {
                message("这条线太短，已取消");
                return;
            }
            pendingRoad.clear();
            pendingRoad.addAll(points);
            finishRoad();
        }
    }

    private List<double[]> regionBrushOutline(Brush brush) {
        List<double[]> outline = new ArrayList<>();
        switch (brush) {
            case RECT -> {
                outline.add(new double[]{brushStartX, brushStartZ});
                outline.add(new double[]{brushCurrentX, brushStartZ});
                outline.add(new double[]{brushCurrentX, brushCurrentZ});
                outline.add(new double[]{brushStartX, brushCurrentZ});
            }
            case CIRCLE -> {
                double radius = Math.hypot(brushCurrentX - brushStartX, brushCurrentZ - brushStartZ);
                if (radius < 1.0D) {
                    return outline;
                }
                int segments = Math.max(16, Math.min(64, (int) Math.round(radius * 1.5D)));
                for (int i = 0; i < segments; i++) {
                    double angle = Math.PI * 2.0D * i / segments;
                    outline.add(new double[]{brushStartX + Math.cos(angle) * radius,
                            brushStartZ + Math.sin(angle) * radius});
                }
            }
            case FREEHAND -> {
                if (brushTrail.size() >= 3) {
                    outline.addAll(brushTrail);
                }
            }
            default -> {
            }
        }
        return outline;
    }

    private List<double[]> roadBrushPoints(Brush brush) {
        if (brush == Brush.LINE) {
            return List.of(new double[]{brushStartX, brushStartZ},
                    new double[]{brushCurrentX, brushCurrentZ});
        }
        if (brush == Brush.FREEHAND) {
            return new ArrayList<>(brushTrail);
        }
        return List.of();
    }

    /** 笔刷切换按钮。 */
    private AtlasButton brushButton(int x, int width, Brush brush, boolean region) {
        AtlasButton button = new AtlasButton(x, panelContentY + 108, width, 16, "笔刷：" + brush.label, null, () -> {
            if (region) {
                regionBrush = brush;
            } else {
                roadBrush = brush;
            }
            message("笔刷：" + brush.label + (brush == Brush.POLY
                    ? "（右键逐点添加）" : "（右键按住拖拽成形，松手生成）"));
            rebuildButtons();
        });
        button.selected = (region ? regionBrush : roadBrush) == brush;
        return button;
    }
    /** 拖拽中的形状预览。 */
    private void renderBrushPreview(GuiGraphics graphics) {
        if (!brushDragging) {
            return;
        }
        List<double[]> preview = tool == Tool.REGION ? regionBrushOutline(brushForCurrentTool())
                : roadBrushPoints(brushForCurrentTool());
        if (preview.size() < 2) {
            return;
        }
        if (tool == Tool.REGION) {
            List<double[]> closed = new ArrayList<>(preview);
            closed.add(preview.get(0));
            drawPolyline(graphics, closed, 3, 0xFF7C8C5A, false);
        } else if (tool == Tool.ROAD) {
            drawPolyline(graphics, preview, 3, 0xFF8C6F52, false);
        }
    }

    // ============================================================ 区域顶点拖拽编辑

    /** 顶点命中测试：{0=顶点 / 1=边中点, 下标}。 */
    private int[] vertexHit(AtlasRegion region, double mouseX, double mouseY) {
        List<double[]> outline = region.outline();
        for (int i = 0; i < outline.size(); i++) {
            double[] point = outline.get(i);
            if (Math.hypot(screenX(point[0]) - mouseX, screenY(point[1]) - mouseY) <= 6.0D) {
                return new int[]{0, i};
            }
        }
        for (int i = 0; i < outline.size(); i++) {
            double[] a = outline.get(i);
            double[] b = outline.get((i + 1) % outline.size());
            double midX = (a[0] + b[0]) / 2.0D;
            double midZ = (a[1] + b[1]) / 2.0D;
            if (Math.hypot(screenX(midX) - mouseX, screenY(midZ) - mouseY) <= 6.0D) {
                return new int[]{1, i};
            }
        }
        return null;
    }

    private void beginVertexDrag(int index) {
        if (vertexEditRegion == null) {
            return;
        }
        vertexDragOutline = new ArrayList<>(vertexEditRegion.outline());
        vertexDragIndex = index;
    }

    private void updateVertexDrag(double worldX, double worldZ) {
        if (vertexDragOutline == null || vertexDragIndex < 0 || vertexDragIndex >= vertexDragOutline.size()) {
            return;
        }
        vertexDragOutline.set(vertexDragIndex, new double[]{worldX, worldZ});
    }

    /** 提交拖动：与同层区域重叠则还原（绝不悄悄破坏"同层不重叠"不变量）。 */
    private void commitVertexDrag() {
        if (vertexDragOutline == null || vertexEditRegion == null) {
            vertexDragIndex = -1;
            vertexDragOutline = null;
            return;
        }
        AtlasRegion updated = vertexEditRegion.withOutline(vertexDragOutline,
                vertexEditRegion.labelX(), vertexEditRegion.labelZ());
        AtlasRegion conflict = AtlasContent.get().overlapsSibling(updated);
        if (conflict != null) {
            message("移动后会与「" + conflict.name() + "」重叠，已还原");
        } else {
            applyRegion(updated);
            vertexEditRegion = updated;
            message("顶点已更新（共 " + vertexDragOutline.size() + " 个顶点）");
        }
        vertexDragOutline = null;
        vertexDragIndex = -1;
    }

    /** 顶点编辑手柄：顶点=实心方块，边中点=侧色方块。 */
    private void renderVertexHandles(GuiGraphics graphics) {
        if (!authorMode || vertexEditRegion == null) {
            return;
        }
        List<double[]> outline = vertexDragOutline != null ? vertexDragOutline : vertexEditRegion.outline();
        for (int i = 0; i < outline.size(); i++) {
            double[] point = outline.get(i);
            int x = (int) Math.round(screenX(point[0]));
            int y = (int) Math.round(screenY(point[1]));
            if (!iconVisible(x, y)) {
                continue;
            }
            blitRect(graphics, x - 3, y - 3, x + 4, y + 4, 0xFF343F3E);
            blitRect(graphics, x - 1, y - 1, x + 2, y + 2, 0xFFF4E4B8);
        }
        for (int i = 0; i < outline.size(); i++) {
            double[] a = outline.get(i);
            double[] b = outline.get((i + 1) % outline.size());
            int x = (int) Math.round(screenX((a[0] + b[0]) / 2.0D));
            int y = (int) Math.round(screenY((a[1] + b[1]) / 2.0D));
            if (!iconVisible(x, y)) {
                continue;
            }
            blitRect(graphics, x - 2, y - 2, x + 3, y + 3, 0xCC7C8C5A);
        }
    }

/** 引路：画整条路线（已走灰、未走高亮两遍画法）+ 目标标记。 */
    private void renderRoute(GuiGraphics graphics) {
        if (!AtlasRoute.has() || minecraft == null || minecraft.player == null) {
            return;
        }
        com.biyi.atlas.map.RoadGraph.Path path = AtlasRoute.path();
        List<double[]> points = path.points();
        if (points.size() < 2) {
            return;
        }
        int progress = Math.max(0, Math.min(points.size() - 2,
                AtlasRoute.progressIndex(minecraft.player.getX(), minecraft.player.getZ())));
        if (progress > 0) {
            drawPolyline(graphics, new ArrayList<>(points.subList(0, progress + 1)), 3, 0x88909090, false);
        }
        List<double[]> remaining = new ArrayList<>(points.subList(progress, points.size()));
        if (remaining.size() >= 2) {
            drawPolyline(graphics, remaining, 5, 0xFFECEDE4, false);
            drawPolyline(graphics, remaining, 2, 0xFF9A5049, false);   // 朱红导航
        }
        // 目标点：墨环
        double[] end = points.get(points.size() - 1);
        int ex = (int) Math.round(screenX(end[0]));
        int ey = (int) Math.round(screenY(end[1]));
        if (iconVisible(ex, ey)) {
            drawInkRing(graphics, ex - 7, ey - 7, 14, 14, 0xFFD4A64A);
        }
    }

    /** 引路 HUD：剩余距离/时间 + 下一段转向指引。 */
    private void renderRouteHud(GuiGraphics graphics) {
        if (!AtlasRoute.has() || minecraft == null || minecraft.player == null) {
            return;
        }
        double px = minecraft.player.getX();
        double pz = minecraft.player.getZ();
        double remaining = AtlasRoute.remainingFrom(px, pz);
        String line1 = "路线 → " + (AtlasRoute.targetName().isEmpty() ? "目标" : "「" + AtlasRoute.targetName() + "」")
                + "  剩余 " + (int) remaining + " 格 · 约 " + AtlasRoute.secondsOf(remaining) + " 秒";
        String line2 = AtlasRoute.instruction(px, pz);
        int available = drawerOpen ? uiFrame().drawer().x()-MARGIN-8 : this.width-MARGIN*2;
        if(available<80)return;
        int width = Math.min(available,Math.max(this.font.width(line1), this.font.width(line2)) + 12);
        int y = this.height - 46;
        blitRect(graphics, MARGIN, y, MARGIN + width, y + 24, 0xCCE8D5A9);
        graphics.drawString(this.font, trimToWidth(line1,width-12), MARGIN + 6, y + 4, 0xFF343F3E, false);
        graphics.drawString(this.font, trimToWidth(line2,width-12), MARGIN + 6, y + 14, 0xFF586561, false);
    }

    /** 信息窗里的"规划路线 / 清除路线"按钮（单槽位切换）。 */
    private void addRouteButton(int x, int y, int width, double worldX, double worldZ, String name) {
        boolean sameTarget = AtlasRoute.has() && !name.isEmpty() && name.equals(AtlasRoute.targetName());
        buttons.add(new AtlasButton(x, y, width, 16, sameTarget ? "清除路线" : "规划路线", null, () -> {
            if (sameTarget) {
                AtlasRoute.clear();
                message("已清除路线");
            } else {
                double fromX = minecraft != null && minecraft.player != null ? minecraft.player.getX() : centerX;
                double fromZ = minecraft != null && minecraft.player != null ? minecraft.player.getZ() : centerZ;
                message(AtlasRoute.plan(dimension, viewLayer, fromX, fromZ, worldX, worldZ, name));
            }
            rebuildButtons();
        }));
    }
    /** 只画"可见段"的连续区段：跨区域的路在锁区边界处自然截断，而不是整条隐藏。 */
    private void drawVisibleRuns(GuiGraphics graphics, AtlasRoad road) {
        List<double[]> points = road.points();
        int segCount = points.size() - 1;
        int runStart = -1;
        for (int segment = 0; segment < segCount; segment++) {
            if (segmentVisible(road, segment)) {
                if (runStart < 0) {
                    runStart = segment;
                }
            } else if (runStart >= 0) {
                drawRun(graphics, road, points, runStart, segment);
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            drawRun(graphics, road, points, runStart, segCount);
        }
    }

    private void drawRun(GuiGraphics graphics, AtlasRoad road, List<double[]> points, int fromSegment,
                         int toSegmentExclusive) {
        if (points.size() < 2) {
            return;
        }
        List<double[]> source;
        if (fromSegment == 0 && toSegmentExclusive == points.size() - 1) {
            source = lodPoints(road);   // 整条可见：用带缓存的整条 LOD，避免每帧重复抽稀
        } else {
            source = com.biyi.atlas.map.RoadGeometry.simplifyPoints(
                    new ArrayList<>(points.subList(fromSegment, toSegmentExclusive + 1)), lodEpsilonBlocks());
        }
        if (source.size() >= 2) {
            int width=com.biyi.atlas.map.RoadStyle.width(road.width(),pixelsPerBlock);
            drawPolyline(graphics,source,width+1,com.biyi.atlas.map.RoadStyle.edge(road.rgb()),road.dashed(),false);
            drawPolyline(graphics,source,width,com.biyi.atlas.map.RoadStyle.INK,road.dashed(),false);
        }
    }

    /** 当前缩放下"1~2 像素"对应的世界容差（LOD 用）。 */
    private double lodEpsilonBlocks() {
        double epsilonPx = pixelsPerBlock >= 6.0D ? 0.4D : pixelsPerBlock >= 1.5D ? 1.0D : 2.0D;
        return epsilonPx / Math.max(0.01D, pixelsPerBlock);
    }

    /** 整条路是否与地图可视区相交（道路级 AABB 剔除，避免为视口外的线白算采样）。 */
    private boolean roadOnScreen(AtlasRoad road) {
        List<double[]> points = road.points();
        if (points.isEmpty()) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            minZ = Math.min(minZ, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxZ = Math.max(maxZ, point[1]);
        }
        if(!authorMode){
            var area=AtlasMapSession.active();if(area==null)return false;
            double[] b=area.bounds();if(maxX<b[0]||minX>b[2]||maxZ<b[1]||minZ>b[3])return false;
        }
        double left = Math.min(screenX(minX), screenX(maxX)) - 8.0D;
        double right = Math.max(screenX(minX), screenX(maxX)) + 8.0D;
        double top = Math.min(screenY(minZ), screenY(maxZ)) - 8.0D;
        double bottom = Math.max(screenY(minZ), screenY(maxZ)) + 8.0D;
        return right >= 0 && left <= this.width && bottom >= 0 && top <= this.height;
    }

    /** 作者手绘地图图层：整张 PNG 按世界坐标贴上，用 UV 裁剪只画可见部分。 */
    private void renderMapImages(GuiGraphics graphics) {
        for (AtlasMapImage image : AtlasContent.get().mapsAt(dimension, viewLayer)) {
            if (!elementVisible(AtlasVisibility.Kind.IMAGE, image.region(), image.centerX(), image.centerZ())) {
                continue;
            }
            double screenW = image.width() * image.blocksPerPixel() * pixelsPerBlock;
            double screenH = image.height() * image.blocksPerPixel() * pixelsPerBlock;
            if (screenW < 2.0D || screenH < 2.0D) {
                continue;
            }
            double startX = screenX(image.originX());
            double startY = screenY(image.originZ());
            int clipX0 = Math.max(MARGIN, (int) Math.floor(startX));
            int clipY0 = Math.max(MARGIN, (int) Math.floor(startY));
            int clipX1 = Math.min(this.width - MARGIN, (int) Math.ceil(startX + screenW));
            int clipY1 = Math.min(this.height - MARGIN, (int) Math.ceil(startY + screenH));
            if (clipX1 <= clipX0 || clipY1 <= clipY0) {
                continue;
            }
            float u = (float) ((clipX0 - startX) / screenW * image.width());
            float v = (float) ((clipY0 - startY) / screenH * image.height());
            int uWidth = Math.max(1, (int) Math.ceil((clipX1 - clipX0) / screenW * image.width()));
            int vHeight = Math.max(1, (int) Math.ceil((clipY1 - clipY0) / screenH * image.height()));
            graphics.setColor(1.0F, 1.0F, 1.0F, image.alpha() / 255.0F);
            ResourceLocation mapTexture = textureOf(image.texture().toString());
                graphics.blit(mapTexture, clipX0, clipY0, clipX1 - clipX0, clipY1 - clipY0,
                    u, v, uWidth, vHeight, image.width(), image.height());
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (image.equals(selectedImage)) {
                drawInkRing(graphics, clipX0, clipY0, clipX1 - clipX0, clipY1 - clipY0, 0xFFD4A64A);
            }
        }
    }

    private record BiomeInfo(int base, float temperature) {
    }

    private final Map<String, BiomeInfo> biomeInfoCache = new HashMap<>();

    /** biome 调色板条目 → 手绘地形类别（带缓存，避免每块瓦片都查注册表）。 */
    private BiomeInfo biomeInfo(String biomeId) {
        BiomeInfo cached = biomeInfoCache.get(biomeId);
        if (cached != null) {
            return cached;
        }
        BiomeInfo info = new BiomeInfo(TerrainCategory.OTHER, 0.8F);
        if (minecraft != null && minecraft.level != null) {
            ResourceLocation id = ResourceLocation.tryParse(biomeId);
            if (id != null) {
                var registry = minecraft.level.registryAccess().registryOrThrow(Registries.BIOME);
                Biome biome = registry.get(id);
                if (biome != null) {
                    Holder<Biome> holder = registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME, id));
                    info = new BiomeInfo(TerrainCategory.classifyBiome(holder, biome),
                            biome.getBaseTemperature());
                }
            }
        }
        biomeInfoCache.put(biomeId, info);
        return info;
    }

    /** 区块存的值 → 手绘地形类别；有调色板＝新数据（biome 下标），没有＝旧存档（旧编码）。 */
    private int categoryFor(AtlasMapData data, int stored, int height) {
        if (data.biomePalette().isEmpty()) {
            return TerrainCategory.decode(stored);
        }
        String biomeId = data.biomeAt(stored);
        if (biomeId.isEmpty()) {
            return TerrainCategory.OTHER;
        }
        if (height >= 110) {
            return TerrainCategory.MOUNTAIN;
        }
        BiomeInfo info = biomeInfo(biomeId);
        if (info.temperature() <= 0.15F && info.base() != TerrainCategory.WATER) {
            return TerrainCategory.SNOW;
        }
        return info.base();
    }

    /** 屏幕 x → 世界 x（像素中心）。 */
    private double worldXOf(double screenX) {
        return centerX + (screenX + 0.5D - this.width / 2.0D) / pixelsPerBlock;
    }

    /** 屏幕 y → 世界 z（像素中心）。 */
    private double worldZOf(double screenY) {
        return centerZ + (screenY + 0.5D - this.height / 2.0D) / pixelsPerBlock;
    }

    private int textureWidth(ResourceLocation texture) {
        return textureSize(texture, true);
    }

    private int textureHeight(ResourceLocation texture) {
        return textureSize(texture, false);
    }

    /** 贴图尺寸缓存：用于把图片按世界坐标铺开。 */
    /**
     * 把内容里存的贴图 id 解析成可用的 ResourceLocation。
     *
     * <p>旧数据里写的是中文/带空格的文件名（例如 {@code textures/generated/region_鼠鼠笑脸.png}），
     * 这种 id 在 Minecraft 里是非法的（ResourceLocation 只允许 [a-z0-9/._-]），
     * 直接用会抛异常/贴图紫色。这里自动回退到 {@code safeName} 版本，新旧数据都能对上。</p>
     */
    private ResourceLocation textureOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ResourceLocation direct = ResourceLocation.tryParse(raw);
        if (direct != null) {
            return direct;
        }
        String fileName = raw.substring(raw.lastIndexOf('/') + 1);
        String bare = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return new ResourceLocation(BiyiAtlas.MODID,
                "textures/generated/" + LocationCapture.safeName(bare) + ".png");
    }
    private int textureSize(ResourceLocation texture, boolean width) {
        Integer cached = IMAGE_SIZES.get(texture);
        if (cached != null) {
            return width ? (cached >> 16) & 0xFFFF : cached & 0xFFFF;
        }
        // 动态注册的贴图（生成的俯视图 / 导入的图片 / 区域映像图）不在 ResourceManager 里，
        // 必须先问 LocationCapture 拿注册时记下的尺寸，否则会退化成 256×256 → 映像图 UV 全错。
        int[] dynamic = LocationCapture.sizeOf(texture);
        if (dynamic != null) {
            int dw = Math.max(1, dynamic[0]);
            int dh = Math.max(1, dynamic[1]);
            IMAGE_SIZES.put(texture, ((dw & 0xFFFF) << 16) | (dh & 0xFFFF));
            return width ? dw : dh;
        }
        int w = 256;
        int h = 256;
        try {
            var resource = minecraft.getResourceManager().getResource(texture);
            if (resource.isPresent()) {
                try (var image = com.mojang.blaze3d.platform.NativeImage.read(resource.get().open())) {
                    w = Math.max(1, image.getWidth());
                    h = Math.max(1, image.getHeight());
                }
            }
        } catch (Exception ignored) {
            // 用默认尺寸
        }
        IMAGE_SIZES.put(texture, ((w & 0xFFFF) << 16) | (h & 0xFFFF));
        return width ? w : h;
    }

    private static final Map<ResourceLocation, Integer> IMAGE_SIZES = new HashMap<>();

    /** 旅行地图底片：给作者画区域时当参考，不写入任何数据。 */
    private void renderJourneyUnderlay(GuiGraphics graphics) {
        if (!journeyUnderlay) {
            return;
        }
        java.nio.file.Path dir = JourneyMapImport.currentLayerDir();
        if (dir == null) {
            return;
        }
        int region = JourneyMapImport.regionSize();
        int minRegionX = Math.floorDiv((int) Math.floor(worldXOf(0)), region);
        int maxRegionX = Math.floorDiv((int) Math.floor(worldXOf(this.width)), region);
        int minRegionZ = Math.floorDiv((int) Math.floor(worldZOf(0)), region);
        int maxRegionZ = Math.floorDiv((int) Math.floor(worldZOf(this.height)), region);
        for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                java.nio.file.Path file = dir.resolve(rx + "," + rz + ".png");
                if (!java.nio.file.Files.isRegularFile(file)) {
                    continue;
                }
                ResourceLocation texture = jmTexture(file, rx, rz);
                if (texture == null) {
                    continue;
                }
                int x0 = (int) Math.round(screenX((double) rx * region));
                int y0 = (int) Math.round(screenY((double) rz * region));
                int x1 = (int) Math.round(screenX((double) (rx + 1) * region));
                int y1 = (int) Math.round(screenY((double) (rz + 1) * region));
                int[] clip = clipToMap(x0, y0, x1, y1);
                if (clip == null) {
                    continue;
                }
                float u = (float) ((clip[0] - x0) / (double) (x1 - x0) * region);
                float v = (float) ((clip[1] - y0) / (double) (y1 - y0) * region);
                int uWidth = Math.max(1, (int) Math.ceil((clip[2] - clip[0]) / (double) (x1 - x0) * region));
                int vHeight = Math.max(1, (int) Math.ceil((clip[3] - clip[1]) / (double) (y1 - y0) * region));
                graphics.setColor(1.0F, 1.0F, 1.0F, 0.9F);
                graphics.blit(texture, clip[0], clip[1], clip[2] - clip[0], clip[3] - clip[1],
                        u, v, uWidth, vHeight, region, region);
            }
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private ResourceLocation jmTexture(java.nio.file.Path file, int rx, int rz) {
        String key = file.toString();
        ResourceLocation cached = JM_TEXTURES.get(key);
        if (cached != null) {
            return cached;
        }
        ResourceLocation texture = new ResourceLocation(BiyiAtlas.MODID,
                "textures/jm/" + Math.abs(key.hashCode()) + "_" + rx + "_" + rz + ".png");
        LocationCapture.register(texture, file);
        JM_TEXTURES.put(key, texture);
        return texture;
    }

    private static final Map<String, ResourceLocation> JM_TEXTURES = new HashMap<>();

    /** 框选矩形（作者用来划定"生成俯视图/导入旅行地图"的范围）。 */
    private void renderPendingRect(GuiGraphics graphics) {
        if (rectStage == 0) {
            return;
        }
        int x1 = (int) Math.round(screenX(rectX1));
        int z1 = (int) Math.round(screenY(rectZ1));
        if (rectStage == 1) {
            drawInkRing(graphics, x1 - 4, z1 - 4, 8, 8, 0xFFD4A64A);
            return;
        }
        int x2 = (int) Math.round(screenX(rectX2));
        int z2 = (int) Math.round(screenY(rectZ2));
        int left = Math.min(x1, x2);
        int top = Math.min(z1, z2);
        int right = Math.max(x1, x2);
        int bottom = Math.max(z1, z2);
        blitRect(graphics, left, top, right, top + 1, 0xFFD4A64A);
        blitRect(graphics, left, bottom - 1, right, bottom, 0xFFD4A64A);
        blitRect(graphics, left, top, left + 1, bottom, 0xFFD4A64A);
        blitRect(graphics, right - 1, top, right, bottom, 0xFFD4A64A);
    }

    /** 简化版 autotile：只处理"这一侧邻块地形不同"，画一条手绘墨线。 */
    private void drawTerrainEdges(GuiGraphics graphics, AtlasMapData data, int chunkX, int chunkZ,
                                  int category, int[] rect) {
        if (neighborCategory(data, chunkX, chunkZ - 1) != category) {
            drawEdgeSprite(graphics, rect, 0);
        }
        if (neighborCategory(data, chunkX + 1, chunkZ) != category) {
            drawEdgeSprite(graphics, rect, 1);
        }
        if (neighborCategory(data, chunkX, chunkZ + 1) != category) {
            drawEdgeSprite(graphics, rect, 2);
        }
        if (neighborCategory(data, chunkX - 1, chunkZ) != category) {
            drawEdgeSprite(graphics, rect, 3);
        }
    }

    private void drawEdgeSprite(GuiGraphics graphics, int[] rect, int index) {
        graphics.setColor(1.0F, 1.0F, 1.0F, 0.7F);
        graphics.blit(TerrainTiles.EDGES, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1],
                (float) (index * TerrainTiles.TILE_PX), 0.0F, TerrainTiles.TILE_PX, TerrainTiles.TILE_PX,
                TerrainTiles.EDGES_W, TerrainTiles.EDGES_H);
    }

    private int neighborCategory(AtlasMapData data, int chunkX, int chunkZ) {
        if (!data.has(chunkX, chunkZ)) {
            return -2;
        }
        int packed = data.get(chunkX, chunkZ);
        return categoryFor(data, AtlasMapData.colorId(packed), AtlasMapData.height(packed));
    }

    /** 地表高度 → 明暗系数（手绘地图的起伏感）。 */
    private static float heightShade(int height) {
        double factor = 1.0D + (height - 64) * 0.0045D;
        return (float) Math.max(0.84D, Math.min(1.14D, factor));
    }

    /** 章节区域：淡填充 + 墨线边界；未解锁时按作者设置画虚线暗边并标注"未解锁"。 */
    /**
     * 选"当前该显示哪张映像图"：视野内**能完整看到的最大区域**（面积最大者）。
     *
     * <p>对应三条规则：缩到能看全大区域 → 大区域图；放大到能看全小区域 → 小区域图；再放大（都看不全）→ 都不显示。</p>
     */
    private String chooseImageryRegion() {
        double halfW = this.width / 2.0D / Math.max(0.01D, pixelsPerBlock);
        double halfH = this.height / 2.0D / Math.max(0.01D, pixelsPerBlock);
        double viewMinX = centerX - halfW;
        double viewMaxX = centerX + halfW;
        double viewMinZ = centerZ - halfH;
        double viewMaxZ = centerZ + halfH;
        String best = null;
        double bestArea = -1.0D;
        for (AtlasRegion region : AtlasContent.get().regionsAt(dimension, viewLayer)) {
            if (region.image() == null || region.image().isEmpty()) {
                continue;
            }
            if (!authorMode && !AtlasUnlocks.isVisible(region.id())) {
                continue;
            }
            double[] bounds = region.bounds();
            boolean fullyVisible = bounds[0] >= viewMinX && bounds[2] <= viewMaxX
                    && bounds[1] >= viewMinZ && bounds[3] <= viewMaxZ;
            if (!fullyVisible) {
                continue;
            }
            double area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]);
            if (area > bestArea) {
                bestArea = area;
                best = region.id();
            }
        }
        return best;
    }

    /** 映像图在世界里的绘制范围（按"缩放 + 偏移"调整，仍按区域多边形裁剪）。 */
    private double[] imageryExtent(AtlasRegion region) {
        double[] bounds = region.bounds();
        double[] transform = AtlasContent.get().imageTransform(region.id());
        double scale = transform == null ? 1.0D : transform[0];
        double offsetX = transform == null ? 0.0D : transform[1];
        double offsetZ = transform == null ? 0.0D : transform[2];
        double cx = (bounds[0] + bounds[2]) / 2.0D;
        double cz = (bounds[1] + bounds[3]) / 2.0D;
        double halfW = (bounds[2] - bounds[0]) / 2.0D * scale;
        double halfH = (bounds[3] - bounds[1]) / 2.0D * scale;
        return new double[]{cx - halfW + offsetX, cz - halfH + offsetZ,
                cx + halfW + offsetX, cz + halfH + offsetZ};
    }

    private void renderRegions(GuiGraphics graphics) {
        // 映像图"按视野呈现"：只画**当前视野内能完整看到的最大区域**的映像图。
        // 缩到能看全大区域 → 画大区域图；放大到能看全小区域 → 画小区域图；再放大 → 都不画。
        for (AtlasRegion region : AtlasContent.get().regionsAt(dimension, viewLayer)) {
            boolean unlocked = AtlasUnlocks.isVisible(region.id());
            // 未解锁时"是否画边界"由作者设置决定，但**迷雾与隐藏不受它影响**（D-15 解耦）
            if (!unlocked && !region.showOutlineWhenLocked() && authorMode) {
                continue;
            }
            // 战争迷雾：玩家侧未解锁区域用"云朵印章"铺满；印章是**屏幕像素定尺**，因此缩放不拉伸
            if (!unlocked && !region.showOutlineWhenLocked() && !authorMode) {
                continue;   // 作者要求未解锁时不示边界：迷雾已画，边界与标签跳过
            }
            // 区域只画边界线，不做纯色填充（填充会压住界面与其它元素）
            int alpha = unlocked ? 0xFF : 0x99;
            int lineWidth = region.mapArea() ? 2 : 1;
            if(region.mapArea() || (authorMode && selectedRegion!=null && selectedRegion.id().equals(region.id())))
            drawClosedPolyline(graphics, region.outline(), unlocked ? lineWidth : 1,
                    (alpha << 24) | region.lineRgb(), !unlocked);

            int labelX = (int) Math.round(screenX(region.labelX()));
            int labelY = (int) Math.round(screenY(region.labelZ()));
            if (iconVisible(labelX, labelY) && (region.mapArea()||pixelsPerBlock>=.18||(selectedRegion!=null&&selectedRegion.id().equals(region.id())))) {
                String label = region.labelText() + (unlocked ? "" : "（未解锁）");
                int width = this.font.width(label);
                if (!reserveMapLabel(labelX - width / 2 - 3, labelY - 4, width + 6, 10)) continue;
                drawMapLabel(graphics,label,labelX-width/2,labelY-3,unlocked?0xFF48514A:0xFF8A7A66);
            }
        }
        if (!pendingRegion.isEmpty()) {
            drawClosedPolyline(graphics, pendingRegion, 1, 0xCC3F7FBF, true);
            for (double[] point : pendingRegion) {
                int px = (int) Math.round(screenX(point[0]));
                int py = (int) Math.round(screenY(point[1]));
                blitRect(graphics, px - 2, py - 2, px + 3, py + 3, 0xFF3F7FBF);
            }
        }
    }

    /** 迷雾贴图里的 8 个云朵单元（UV 网格位置），羽化时按索引轮换取用。 */
    private static final int[][] FOG_TILES = {
            {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    /**
     * 战争迷雾：在区域包围盒内按**屏幕像素网格**盖云朵印章。
     *
     * <p>这样做的三个好处：①印章尺寸不随缩放变化（以前"整张贴图按世界包围盒拉伸"会导致放大变糊、
     * 缩小变稀）；②网格铺满整个区域内部，不会只剩边缘一圈；③印章中心在区域外但离边界不到半个印章时
     * 也画，于是边界是参差的云朵形状，而不是硬切。</p>
     */
    private void drawFogStamps(GuiGraphics graphics, AtlasRegion region) {
        double[] b=region.bounds();
        if(screenX(b[2])<MARGIN||screenX(b[0])>width-MARGIN||screenY(b[3])<MARGIN||screenY(b[1])>height-MARGIN)return;
        double progress=revealRegion!=null&&revealRegion.id().equals(region.id())?revealProgress():-1;
        if(progress>=1)return;
        if(!AtlasFogRenderer.draw(graphics,region,centerX,centerZ,pixelsPerBlock,width,height,progress))fillPolygon(graphics,region.outline(),0xFFE4E8DF);
    }

    /** Kept only as legacy atlas compatibility; new rendering uses cached polygon masks. */
    private void drawLegacyFogStamps(GuiGraphics graphics, AtlasRegion region) {
        int texW = textureWidth(FOG_TEXTURE);
        int texH = textureHeight(FOG_TEXTURE);
        if (texW <= 0 || texH <= 0 || region.outline().size() < 3) {
            return;
        }
        int uv = Math.max(64, Math.min(texW, texH) / 3);
        int stamp = 96;
        int step = (int) (stamp * 0.62D);
        double[] bounds = region.bounds();
        int left = (int) Math.floor(screenX(bounds[0])) - stamp;
        int right = (int) Math.ceil(screenX(bounds[2])) + stamp;
        int top = (int) Math.floor(screenY(bounds[1])) - stamp;
        int bottom = (int) Math.ceil(screenY(bounds[3])) + stamp;
        int row = 0;
        for (int y = top; y <= bottom; y += step) {
            int col = 0;
            for (int x = left; x <= right; x += step) {
                int cx = x + stamp / 2;
                int cy = y + stamp / 2;
                int cellIndex = (row * 3 + col) % FOG_TILES.length;
                col++;
                if (!iconVisible(cx, cy)) {
                    continue;
                }
                double worldX = worldXOf(cx);
                double worldZ = worldZOf(cy);
                boolean inside = region.contains(worldX, worldZ);
                if (!inside && distanceToOutline(worldX, worldZ, region.outline()) * pixelsPerBlock > stamp * 0.5D) {
                    continue;   // 离边界太远的外部印章不画
                }
                int[] cell = FOG_TILES[cellIndex];
                int u0 = Math.max(0, Math.min(texW - uv, texW / 2 + cell[0] * (texW / 3) - uv / 2));
                int v0 = Math.max(0, Math.min(texH - uv, texH / 2 + cell[1] * (texH / 3) - uv / 2));
                int[] rect = clipToMap(x, y, x + stamp, y + stamp);
                if (rect == null) {
                    continue;
                }
                graphics.blit(FOG_TEXTURE, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1],
                        u0, v0, uv, uv, texW, texH);
            }
            row++;
        }
    }

    /** 点到折线（多边形边界）的最短距离（世界坐标）。 */
    private static double distanceToOutline(double x, double z, List<double[]> outline) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < outline.size(); i++) {
            double[] a = outline.get(i);
            double[] b = outline.get((i + 1) % outline.size());
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, ((x - a[0]) * dx + (z - a[1]) * dz) / lengthSq));
            best = Math.min(best, Math.hypot(x - (a[0] + t * dx), z - (a[1] + t * dz)));
        }
        return best;
    }

    private void drawClosedPolyline(GuiGraphics graphics, List<double[]> outline, int width, int argb,
                                    boolean dashed) {        if (outline.size() < 2) {
            return;
        }
        List<double[]> closed = new ArrayList<>(outline);
        closed.add(outline.get(0));
        drawPolyline(graphics, closed, width, argb, dashed);
    }

    /** 多边形扫描线填充（逐行一条条 blit 出来，不依赖 fill）。 */
    private void fillPolygon(GuiGraphics graphics, List<double[]> outline, int argb) {
        int count = outline.size();
        if (count < 3) {
            return;
        }
        double[] sx = new double[count];
        double[] sy = new double[count];
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            sx[i] = screenX(outline.get(i)[0]);
            sy[i] = screenY(outline.get(i)[1]);
            minY = Math.min(minY, sy[i]);
            maxY = Math.max(maxY, sy[i]);
        }
        int y0 = Math.max(MARGIN, (int) Math.floor(minY));
        int y1 = Math.min(this.height - MARGIN, (int) Math.ceil(maxY));
        if (y1 <= y0 || y1 - y0 > 1200) {
            return;
        }
        List<Double> crossings = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            crossings.clear();
            for (int i = 0, j = count - 1; i < count; j = i++) {
                if ((sy[i] > y) != (sy[j] > y)) {
                    double t = (y - sy[i]) / (sy[j] - sy[i]);
                    crossings.add(sx[i] + t * (sx[j] - sx[i]));
                }
            }
            if (crossings.size() < 2) {
                continue;
            }
            crossings.sort(Double::compareTo);
            for (int k = 0; k + 1 < crossings.size(); k += 2) {
                blitRect(graphics, (int) Math.floor(crossings.get(k)), y,
                        (int) Math.ceil(crossings.get(k + 1)), y + 1, argb);
            }
        }
    }

    /** 道路：沿折线铺小方块画出来；虚线按固定间隔跳点。 */
    private void renderRoads(GuiGraphics graphics) {
        for (AtlasRoad road : AtlasContent.get().roadsAt(dimension, viewLayer)) {
            if(!roadOnScreen(road))continue;
            if (!roadVisible(road)) {
                continue;
            }
            if (!authorMode && blitBudget <= 0) {
                break;   // 预算耗尽：剩余道路本帧不画
            }
            if (!roadOnScreen(road)) {
                continue;   // 道路级视口剔除
            }
            drawVisibleRuns(graphics, road);
            // 节点标记（岔口/端点/交界/锚点）
            if (pixelsPerBlock >= 0.35D && (authorMode || AtlasSettings.showNodesToPlayers())) {
                List<double[]> roadPoints = road.points();
                for (AtlasRoadNode node : road.nodes()) {
                    if (node.index() < 0 || node.index() >= roadPoints.size()) {
                        continue;
                    }
                    if (!authorMode && !segmentVisible(road,
                            Math.min(node.index(), Math.max(0, roadPoints.size() - 2)))) {
                        continue;   // 所在段不可见 → 节点也不画（点选同样跳过）
                    }
                    double[] point = roadPoints.get(node.index());
                    int nx = (int) Math.round(screenX(point[0]));
                    int ny = (int) Math.round(screenY(point[1]));
                    if (!iconVisible(nx, ny)) {
                        continue;
                    }
                    blitRect(graphics, nx - 3, ny - 3, nx + 4, ny + 2, 0xFF343F3E);
                    blitRect(graphics, nx - 2, ny - 2, nx + 3, ny + 1, 0xFFE1E5DF);
                    if (selectedNode != null && selectedNode.id().equals(node.id())) {
                        drawInkRing(graphics, nx - 6, ny - 6, 12, 12, 0xFFD4A64A);
                    }
                }
            }
        }
        if (!pendingRoad.isEmpty()) {
            drawPolyline(graphics, pendingRoad, 3, 0xCC3F7FBF, true);
        }
    }

    private void drawPolyline(GuiGraphics graphics, List<double[]> points, int width, int argb, boolean dashed) {
        drawPolyline(graphics, points, width, argb, dashed, false);
    }

    private void drawPolyline(GuiGraphics graphics, List<double[]> points, int width, int argb,
                              boolean dashed, boolean halo) {
        if (points.size() < 2 || blitBudget <= 0) return;
        if (halo && blitBudget > 32) {
            int available = blitBudget;
            blitBudget = available / 2;
            drawPolyline(graphics, points, width + 2, 0xFFECEDE4, dashed, false);
            blitBudget = available - (available / 2 - blitBudget);
        }
        double travelled = 0;
        double dashOn = Math.max(4, 6 / pixelsPerBlock);
        double dashPeriod = Math.max(7, 10 / pixelsPerBlock);
        try {
            for (int i = 0; i + 1 < points.size() && blitBudget > 0; i++) {
                double[] a = points.get(i), b = points.get(i + 1);
                double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (length < 1e-8) continue;
                double x1 = screenX(a[0]), y1 = screenY(a[1]);
                double x2 = screenX(b[0]), y2 = screenY(b[1]);
                double[] clipped = com.biyi.atlas.map.MapStroke.clip(x1, y1, x2, y2,
                        MARGIN - width, MARGIN - width, this.width - MARGIN + width, this.height - MARGIN + width);
                if (clipped != null) {
                    double start = clipped[0] * length, end = clipped[1] * length;
                    while (start < end && blitBudget > 0) {
                        double finish = end;
                        if (dashed) {
                            double phase = (travelled + start) % dashPeriod;
                            if (phase >= dashOn) { start += dashPeriod - phase + 1e-7; continue; }
                            finish = Math.min(end, start + dashOn - phase);
                        }
                        double t1 = start / length, t2 = finish / length;
                        var spans = com.biyi.atlas.map.MapStroke.spans(
                                x1 + (x2 - x1) * t1, y1 + (y2 - y1) * t1,
                                x1 + (x2 - x1) * t2, y1 + (y2 - y1) * t2, width,
                                MARGIN, MARGIN, this.width - MARGIN, this.height - MARGIN);
                        for (var span : spans) {
                            if (blitBudget-- <= 0) break;
                            blitRect(graphics, span.left(), span.top(), span.right(), span.top() + 1, argb);
                        }
                        start = finish + 1e-7;
                    }
                }
                travelled += length;
            }
        } finally {
            graphics.setColor(1, 1, 1, 1);
        }
    }
    /** 渲染 LOD：按缩放给一个**屏幕像素容差**，换算成世界容差后抽稀（带缓存，按修订号+缩放档失效）。 */
    private List<double[]> lodPoints(AtlasRoad road) {
        double epsilonPx = pixelsPerBlock >= 6.0D ? 0.4D : pixelsPerBlock >= 1.5D ? 1.0D : 2.0D;
        double epsilonBlocks = epsilonPx / Math.max(0.01D, pixelsPerBlock);
        if (epsilonBlocks <= 0.05D) {
            return road.points();
        }
        int revision = AtlasContent.get().contentRevision();
        int zoomBucket = (int) Math.round(Math.log(Math.max(0.01D, pixelsPerBlock)) / Math.log(1.6D));
        if (revision != lodCacheRevision || zoomBucket != lodCacheZoomBucket) {
            lodCache.clear();
            lodCacheRevision = revision;
            lodCacheZoomBucket = zoomBucket;
        }
        String key = road.key() + "|" + zoomBucket;
        List<double[]> cached = lodCache.get(key);
        if (cached == null) {
            cached = com.biyi.atlas.map.RoadGeometry.simplifyPoints(road.points(), epsilonBlocks);
            lodCache.put(key, cached);
        }
        return cached;
    }

    /** 地点 / 传送点：图标 + 名称，追踪中的加一圈高亮。 */
    private void renderPlaces(GuiGraphics graphics) {
        AtlasTracker.Target target = AtlasTracker.target();
        for (AtlasPlace place : AtlasContent.get().placesAt(dimension, viewLayer)) {
            if (!elementVisible(AtlasVisibility.Kind.PLACE, place.region(), place.x(), place.z())) {
                continue;
            }
            int x = (int) Math.round(screenX(place.x()));
            int y = (int) Math.round(screenY(place.z()));
            if (!iconVisible(x, y)) {
                continue;
            }
            boolean hovered = place.equals(hoveredPlace);
            boolean tracked = target != null && target.name().equals(place.name())
                    && target.dimension().equals(place.dimension());
            if (tracked) {
                blitRect(graphics, x - 12, y - 22, x + 12, y + 6, 0x66FFD24A);
            }
            if (hovered || place.equals(selectedPlace)) {
                boolean selected = place.equals(selectedPlace);
                drawInkRing(graphics, x - 10, y - 18, 20, 20, selected ? 0xFFD4A64A : 0xFF586561);
            }
            ResourceLocation texture = iconTexture(place.icon());
            if(!AtlasMapSymbols.marker(graphics,place.icon(),x-8,y-16,16))graphics.blit(texture, x - 8, y - 16, 16, 16, 0.0F, 0.0F, 32, 32, 32, 32);
            if (place.alwaysLabel() || hovered) {
                int labelWidth = this.font.width(place.name());
                if (hovered || reserveMapLabel(x - labelWidth / 2, y + 3, labelWidth, 9)) {
                    graphics.drawString(this.font, place.name(), x - labelWidth / 2, y + 3, 0xFF343F3E, false);
                }
            }
        }
    }

    /** 玩家标的路标点：图标 + 名称（悬停时高亮）。 */
    private void renderWaypoints(GuiGraphics graphics) {
        AtlasMapData data = AtlasStorage.get(dimension);
        for (AtlasWaypoint waypoint : data.waypoints()) {
            if (waypoint.layer() != viewLayer) {
                continue;
            }
            if (!elementVisible(AtlasVisibility.Kind.WAYPOINT, null, waypoint.x(), waypoint.z())) {
                continue;
            }
            int x = (int) Math.round(screenX(waypoint.x()));
            int y = (int) Math.round(screenY(waypoint.z()));
            if (!iconVisible(x, y)) {
                continue;
            }
            boolean hovered = waypoint.equals(hoveredWaypoint);
            boolean selected = waypoint.equals(selectedWaypoint);
            if (hovered || selected) {
                drawInkRing(graphics, x - 9, y - 17, 18, 18, selected ? 0xFFD4A64A : 0xFF586561);
            }
            ResourceLocation texture = iconTexture(waypoint.displayIcon());
            if(!AtlasMapSymbols.marker(graphics,waypoint.displayIcon(),x-8,y-16,16))graphics.blit(texture, x - 8, y - 16, 16, 16, 0.0F, 0.0F, 32, 32, 32, 32);
            if (!loggedFirstWaypoint) {
                loggedFirstWaypoint = true;
                LOGGER.info("[BiyiAtlas] first waypoint '{}' world=({},{}) -> screen=({},{})",
                        waypoint.name(), waypoint.x(), waypoint.z(), x, y);
            }
            String label = waypoint.name();
            if (!label.isEmpty()) {
                int labelWidth = this.font.width(label);
                if((hovered||selected||pixelsPerBlock>=.8)&&reserveMapLabel(x-labelWidth/2,y+3,labelWidth,9))
                    drawMapLabel(graphics,label,x-labelWidth/2,y+3,0xFF48514A);
            }
        }
    }

    /** 作者模式的工具栏：当前工具高亮，底部列出快捷键。 */
    private void renderAuthorToolbar(GuiGraphics graphics, int left, int top) {
        int y = top + 40;
        for (Tool candidate : Tool.values()) {
            boolean active = candidate == tool;
            int color = active ? 0xFF8C4A44 : 0xFF65716C;
            String line = (candidate.ordinal() + 1) + " " + candidate.label;
            graphics.drawString(this.font, line, left + 12, y, color, false);
            if (active) {
                graphics.drawString(this.font, "◀", left + 4, y, color, false);
            }
            y += 12;
        }
        int extra = y + 4;
        graphics.drawString(this.font, "R " + (AtlasRoadRecorder.isRecording() ? "结束记录" : "开始记录轨迹"),
                left + 12, extra, 0xFF65716C, false);
        graphics.drawString(this.font, "E 导出内容", left + 12, extra + 12, 0xFF65716C, false);
        graphics.drawString(this.font, "Shift+右键 删除元素", left + 12, extra + 24, 0xFF65716C, false);
    }

    private void drawMapLabel(GuiGraphics g,String text,int x,int y,int color){
        g.drawString(font,text,x-1,y,0xA6EEEDE1,false);g.drawString(font,text,x+1,y,0xA6EEEDE1,false);
        g.drawString(font,text,x,y-1,0xA6EEEDE1,false);g.drawString(font,text,x,y+1,0xA6EEEDE1,false);
        g.drawString(font,text,x,y,color,false);
    }
    private void renderPlayerArrow(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        float x = (float) screenX(minecraft.player.getX());
        float y = (float) screenY(minecraft.player.getZ());
        if (!iconVisible((int) x, (int) y)) {
            return;
        }
        AtlasMapSymbols.player(graphics,(int)x,(int)y,minecraft.player.getYRot(),minecraft.player.getSkinTextureLocation());
    }

    private void renderTexts(GuiGraphics g,int left,int top,int right,int bottom) {
        var header=uiFrame().header();
        AtlasUiTheme.panel(g,header.x(),header.y(),header.width(),header.height());
        String area=viewArea==null?"选择地图区域":viewArea.name();
        String title=(authorMode?"作者工作台 · ":"图志 · ")+area;
        g.drawString(font,trimToWidth(title,Math.max(40,this.width-160)),16,16,0xFF343F3E,false);
        String scale=(authorMode?AtlasAiBridge.connectionBadge()+" · ":"")+String.format("×%.2f",pixelsPerBlock);
        g.drawString(font,scale,this.width-font.width(scale)-18,16,0xFF65716C,false);
        String footer=authorMode?"在画布编辑 · 右键加点 · 回车完成":"拖动地图 · 滚轮缩放 · M 关闭";
        if(authorMode&&!AtlasMapSession.preparation().startsWith("尚未"))footer=AtlasMapSession.preparation();
        if(!authorMode)footer=AtlasMapSession.status();
        g.drawString(font,trimToWidth(footer,this.width-32),16,this.height-14,0xFF65716C,false);
    }

    private double screenX(double worldX) {
        return this.width / 2.0D + (worldX - centerX) * pixelsPerBlock;
    }

    private double screenY(double worldZ) {
        return this.height / 2.0D + (worldZ - centerZ) * pixelsPerBlock;
    }

    private void setZoom(double value) {
        pixelsPerBlock = Math.max(AtlasStyle.INSTANCE.zoomMin(),
                Math.min(AtlasStyle.INSTANCE.zoomMax(), value));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if(!mapDragAllowed)return true;
        double dragWorldX = centerX + (mouseX - this.width / 2.0D) / pixelsPerBlock;
        double dragWorldZ = centerZ + (mouseY - this.height / 2.0D) / pixelsPerBlock;
        if (button == 0 && vertexDragIndex >= 0) {
            updateVertexDrag(dragWorldX, dragWorldZ);   // 拖顶点：不移动地图
            return true;
        }
        if (button == 1 && brushDragging) {
            updateBrush(dragWorldX, dragWorldZ);        // 拖笔刷：实时更新形状
            return true;
        }
        if (button == 0) {
            centerX -= dragX / pixelsPerBlock;
            centerZ -= dragY / pixelsPerBlock;
            clampCenter();   // 玩家侧：平移不越出当前大区域
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mapDragAllowed=false;
        double releaseWorldX = centerX + (mouseX - this.width / 2.0D) / pixelsPerBlock;
        double releaseWorldZ = centerZ + (mouseY - this.height / 2.0D) / pixelsPerBlock;
        if (button == 0 && vertexDragIndex >= 0) {
            commitVertexDrag();
            rebuildButtons();
            return true;
        }
        if (button == 1 && brushDragging) {
            updateBrush(releaseWorldX, releaseWorldZ);
            finishBrush();
            rebuildButtons();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if(revealing()){skipReveal();return true;}
        if(inDrawer(mouseX,mouseY)){drawerScroll-=(int)(delta*24);arrangeUi();return true;}
        if(uiFrame().header().contains(mouseX,mouseY))return true;
        double previous = pixelsPerBlock;
        double next = Math.max(AtlasStyle.INSTANCE.zoomMin(),
                Math.min(AtlasStyle.INSTANCE.zoomMax(), previous * Math.pow(1.18D, delta)));
        if (next == previous) {
            return true;
        }
        // 朝光标缩放：光标下的世界坐标保持不动
        double worldX = centerX + (mouseX - this.width / 2.0D) / previous;
        double worldZ = centerZ + (mouseY - this.height / 2.0D) / previous;
        pixelsPerBlock = next;
        clampZoom();   // 玩家侧：缩不出去，至少能看到当前大区域完整边界
        setCenter(worldX - (mouseX - this.width / 2.0D) / pixelsPerBlock,
                worldZ - (mouseY - this.height / 2.0D) / pixelsPerBlock);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if(revealing()){skipReveal();return true;}
        mapDragAllowed=false;
        for (AtlasButton atlasButton : buttons) {
            if (atlasButton.hit(mouseX, mouseY)) {
                atlasButton.action.run();
                return true;
            }
        }
        if (insidePanels(mouseX, mouseY)) {
            return true;
        }
        mapDragAllowed=true;
        double hoverWorldX = centerX + (mouseX - this.width / 2.0D) / pixelsPerBlock;
        double hoverWorldZ = centerZ + (mouseY - this.height / 2.0D) / pixelsPerBlock;
        // 顶点编辑：拖顶点 / 点边中点插入 / Shift+点顶点删除
        if (authorMode && button == 0 && vertexEditRegion != null) {
            int[] hit = vertexHit(vertexEditRegion, mouseX, mouseY);
            if (hit != null) {
                List<double[]> outline = new ArrayList<>(vertexEditRegion.outline());
                if (hit[0] == 0) {
                    if (hasShiftDown()) {
                        if (outline.size() <= 3) {
                            message("三个顶点是最少，不能再删");
                            return true;
                        }
                        outline.remove(hit[1]);
                        AtlasRegion trimmed = vertexEditRegion.withOutline(outline,
                                vertexEditRegion.labelX(), vertexEditRegion.labelZ());
                        AtlasRegion conflict = AtlasContent.get().overlapsSibling(trimmed);
                        if (conflict != null) {
                            message("删除后会与「" + conflict.name() + "」重叠，已取消");
                            return true;
                        }
                        applyRegion(trimmed);
                        vertexEditRegion = trimmed;
                        message("已删除顶点（剩 " + outline.size() + " 个）");
                    } else {
                        beginVertexDrag(hit[1]);
                    }
                    return true;
                }
                double[] a = outline.get(hit[1]);
                double[] b = outline.get((hit[1] + 1) % outline.size());
                outline.add(hit[1] + 1, new double[]{(a[0] + b[0]) / 2.0D, (a[1] + b[1]) / 2.0D});
                AtlasRegion inserted = vertexEditRegion.withOutline(outline,
                        vertexEditRegion.labelX(), vertexEditRegion.labelZ());
                applyRegion(inserted);
                vertexEditRegion = inserted;
                beginVertexDrag(hit[1] + 1);
                message("已插入顶点，继续拖动即可定位");
                return true;
            }
        }
        // 笔刷：右键按下开始拖拽成形，松手生成（区域圆形/矩形/手绘、道路直线/手绘）
        if (authorMode && button == 1 && brushIsDrag()) {
            beginBrush(hoverWorldX, hoverWorldZ);
            return true;
        }
        if (button == 0) {
            pickAt(mouseX, mouseY);
            return true;
        }
        if (button == 1) {
            double worldX = centerX + (mouseX - this.width / 2.0D) / pixelsPerBlock;
            double worldZ = centerZ + (mouseY - this.height / 2.0D) / pixelsPerBlock;
            if (authorMode && hasShiftDown() && hasSelection()) {
                deleteSelection();
                return true;
            }
            // 玩家模式：右键不再"标路标"，右键只用于查看/编辑（符合"路标由作者用魔杖设置"）
            switch (authorMode ? tool : Tool.SELECT) {
                case PLACE -> addPlace(worldX, worldZ, AtlasPlace.TYPE_POI);
                case TELEPORT -> addPlace(worldX, worldZ, AtlasPlace.TYPE_TELEPORT);
                case ROAD -> addRoadPoint(worldX, worldZ);
                case REGION -> addRegionPoint(worldX, worldZ);
                case NODE -> insertNodeOnSelectedRoad(worldX, worldZ);
                case RECT -> {
                    if (rectStage == 0) {
                        rectX1 = worldX;
                        rectZ1 = worldZ;
                        rectStage = 1;
                        message("已定第一个角，再点对角");
                    } else {
                        rectX2 = worldX;
                        rectZ2 = worldZ;
                        rectStage = 2;
                        hasRect = true;
                        message("框选完成：点「生成(框选)」或「旅行图(框选)」");
                    }
                    rebuildButtons();
                }
                case WAYPOINT -> addWaypoint(worldX, worldZ);
                case UNLOCK -> toggleRegionAt(worldX, worldZ);
                case SELECT -> pickAt(mouseX, mouseY);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean insidePanels(double mouseX,double mouseY) {
        var frame=uiFrame();
        return frame.header().contains(mouseX,mouseY)||inDrawer(mouseX,mouseY);
    }

    /** 图鉴魔杖要用的"最近选中的区域"。 */
    private static AtlasRegion lastSelectedRegion;

    /** 旅行地图底片（作者画区域时的参考底图，只显示不写入）。 */
    private boolean journeyUnderlay;

    public static AtlasRegion lastSelectedRegion() {
        return lastSelectedRegion;
    }

    public static void setLastSelectedRegion(AtlasRegion region) {
        lastSelectedRegion = region;
    }

    private boolean hasSelection() {
        return selectedPlace != null || selectedRoad != null || selectedRegion != null
                || selectedWaypoint != null || selectedImage != null || selectedNode != null;
    }

    /** 点选：最近的元素成为选中项（不显示白色块，用墨色描边表示选中）。 */
    private void pickAt(double mouseX, double mouseY) {
        selectedPlace = null;
        selectedRoad = null;
        selectedRegion = null;
        selectedWaypoint = null;
        double best = 14.0D;
        for (AtlasPlace place : AtlasContent.get().placesAt(dimension, viewLayer)) {
            if (!elementVisible(AtlasVisibility.Kind.PLACE, place.region(), place.x(), place.z())) {
                continue;
            }
            if (!place.interactive() && !authorMode) {
                continue;   // 纯装饰地点：玩家不可点选（作者仍可选中以便编辑）
            }
            double distance = Math.hypot(screenX(place.x()) - mouseX, screenY(place.z()) - mouseY);
            if (distance <= best) {
                best = distance;
                selectedPlace = place;
                selectedWaypoint = null;
            }
        }
        for (AtlasWaypoint waypoint : AtlasStorage.get(dimension).waypoints()) {
            if (waypoint.layer() != viewLayer || !elementVisible(AtlasVisibility.Kind.WAYPOINT, null, waypoint.x(), waypoint.z())) {
                continue;
            }
            double distance = Math.hypot(screenX(waypoint.x()) - mouseX, screenY(waypoint.z()) - mouseY);
            if (distance <= best) {
                best = distance;
                selectedWaypoint = waypoint;
                selectedPlace = null;
            }
        }
        if (selectedPlace == null && selectedWaypoint == null) {
            // 节点优先于道路（命中半径 10）：玩家侧默认不可选（节点是作者的编辑辅助）
            double bestNode = 10.0D;
            if (authorMode || AtlasSettings.showNodesToPlayers()) {
            for (AtlasRoad road : AtlasContent.get().roadsAt(dimension, viewLayer)) {
                if (!roadVisible(road)) {
                    continue;
                }
                for (AtlasRoadNode node : road.nodes()) {
                    if (node.index() < 0 || node.index() >= road.points().size()) {
                        continue;
                    }
                    if (!segmentVisible(road, Math.min(node.index(), Math.max(0, road.points().size() - 2)))) {
                        continue;   // 逐段可见性：锁区内的节点不可选中
                    }
                    double[] point = road.points().get(node.index());
                    double distance = Math.hypot(screenX(point[0]) - mouseX, screenY(point[1]) - mouseY);
                    if (distance <= bestNode) {
                        bestNode = distance;
                        selectedNode = node;
                        selectedRoad = road;
                    }
                }
            }
            if (selectedNode != null) {
                rebuildButtons();
                return;
            }
            }   // 节点默认不显示给玩家：关闭节点命中作用域
            for (AtlasRoad road : AtlasContent.get().roadsAt(dimension, viewLayer)) {
                if (!roadVisible(road)) {
                    continue;
                }
                List<double[]> points = road.points();
                for (int i = 0; i + 1 < points.size(); i++) {
                    if (!segmentVisible(road, i)) {
                        continue;   // 逐段可见性：看不见的段不能点中
                    }
                    double distance = distanceToSegment(mouseX, mouseY,
                            screenX(points.get(i)[0]), screenY(points.get(i)[1]),
                            screenX(points.get(i + 1)[0]), screenY(points.get(i + 1)[1]));
                    if (distance <= 6.0D) {
                        selectedRoad = road;
                        // 注意：这里必须重建按钮，否则按钮列表会停留在上一次选中项（曾经因此"道路显示区域按钮"）
                        rebuildButtons();
                        return;
                    }
                }
            }
            double worldX = centerX + (mouseX - this.width / 2.0D) / pixelsPerBlock;
            double worldZ = centerZ + (mouseY - this.height / 2.0D) / pixelsPerBlock;
            AtlasRegion region = AtlasContent.get().regionAt(dimension, viewLayer, worldX, worldZ);
            if (region != null) {
                selectedRegion = region;
                setLastSelectedRegion(region);
            }
        }
        // 选中态变了就必须重建按钮列表，否则信息窗里的"追踪/定位/传送"按钮不会出现
        rebuildButtons();
    }

    private double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq < 1.0E-6D) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((px - x1) * dx + (py - y1) * dy) / lengthSq));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private void deleteSelection() {
        if (selectedPlace != null) {
            AtlasContent.get().removePlace(selectedPlace);
            message("已删除地点：" + selectedPlace.name());
            selectedPlace = null;
        } else if (selectedRoad != null) {
            AtlasContent.get().removeRoad(selectedRoad);
            message("已删除道路：" + selectedRoad.name());
            selectedRoad = null;
        } else if (selectedRegion != null) {
            AtlasRegion target = selectedRegion;
            // 级联删除**全部后代**（不只一层），并清理所有引用 + 写墓碑
            List<AtlasRegion> descendants = AtlasContent.get().descendantsOf(target.id());
            int cleared = 0;
            for (AtlasRegion descendant : descendants) {
                cleared += AtlasContent.get().deleteRegionCascade(descendant.id());
            }
            cleared += AtlasContent.get().deleteRegionCascade(target.id());
            message("已删除区域：" + target.name()
                    + (descendants.isEmpty() ? "" : "（含 " + descendants.size() + " 个子区域）")
                    + (cleared == 0 ? "" : "，已解除 " + cleared + " 处要素归属"));
            selectedRegion = null;
            setLastSelectedRegion(null);
            if (viewArea != null && viewArea.id().equals(target.id())) {
                viewArea = null;                 // 删掉正在查看的大区域：不再用它钳制视图
            }
            expandedAreas.remove(target.id());
            refreshViewArea();
        } else if (selectedWaypoint != null) {
            AtlasStorage.get(dimension).removeWaypoint(selectedWaypoint);
            AtlasStorage.markDirty();
            message("已删除路标：" + selectedWaypoint.name());
            selectedWaypoint = null;
        } else if (selectedImage != null) {
            message("已删除地点图：" + selectedImage.name());
            AtlasContent.get().removeMapImage(selectedImage);
            selectedImage = null;
        }
        AtlasContent.get().saveAuthored();
        AtlasStorage.flush();
        rebuildButtons();
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if(insidePanels(mouseX,mouseY)){hoveredPlace=null;hoveredWaypoint=null;}else updateHover(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    // ---------------------------------------------------------------- 面板与按钮

    @Override
    public void init() {
        refreshViewArea();
        rebuildButtons();
    }

    /** 可放置的素材（对应 textures/atlas/marker/<icon>.png）。 */
    private static final List<String> PLACEABLE_ICONS = List.of(
            "town", "village", "camp", "tower", "ruin", "shop", "quest", "danger",
            "portal", "pin", "flag", "tree", "forest", "mountain", "bridge", "gate");

    /** 面板上一个可点按钮。 */
    private final class AtlasButton {
        int x;
        int y;
        int w;
        int h;
        final String label;
        final ResourceLocation icon;
        final Runnable action;
        boolean selected;
        boolean visible=true;
        final UiSection section=buildingSection;
        final int sourceX,sourceY,sourceW,sourceH;

        AtlasButton(int x, int y, int w, int h, String label, ResourceLocation icon, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = section==UiSection.VIEW?switch(label){
                case "▲"->"上一层";case "▼"->"下一层";case "◀"->"前一维度";case "▶"->"后一维度";
                case "－"->"缩小";case "＋"->"放大";default->label;
            }:label;
            this.icon = icon;
            this.action = action;
            sourceX=x;sourceY=y;sourceW=w;sourceH=h;
        }

        boolean hit(double mouseX, double mouseY) {
            return visible && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    // ---------------------------------------------------------------- 新版面板
    // 布局：右上角＝显示控制（层/维度/缩放，所有玩家可见）
    //       底部＝功能面板（玩家只有「路标」页；作者另有 放置/编辑/区域）

    private static final int DISPLAY_PANEL_W = 150;
    private static final int DISPLAY_PANEL_H = 72;
    private static final int BOTTOM_PANEL_W = 340;
    private static final int BOTTOM_PANEL_H = 96;
    private static final int MAX_WAYPOINT_ROWS = 2000;
    private static final int MAX_AREA_ROWS = 2000;
    private static final int SELECTION_WINDOW_W = 360;
    private static final int SELECTION_WINDOW_H = 96;

    /** 当前"大区域"（地图可移动范围 / 可切换单位）。 */
    private AtlasRegion viewArea;
    /** 右上"区域"小节是否展开。 */
    private boolean regionPanelOpen;
    /** 右上"路标"小节是否展开。 */
    private boolean waypointPanelOpen;
    /** 左上角"设置"面板是否展开（作者地图魔杖）。 */
    private boolean settingsPanelOpen;

    /** 底部面板内容区起点（笔刷按钮要用）。 */
    private int panelContentY;

    /** 正在做顶点编辑的区域 / 正在拖动的顶点下标与临时轮廓。 */
    /** 正在调整映像图的区域（拖动=移动；Shift+滚轮=缩放）。 */
    private AtlasRegion imageAdjustRegion;

    /** 删除区域二次确认（有子区域时）。 */
    private String confirmRegionDelete;
    private AtlasRegion vertexEditRegion;
    private int vertexDragIndex = -1;
    private List<double[]> vertexDragOutline;
    /** 笔刷（区域/道路绘制方式）与拖拽绘制状态。 */
    private Brush regionBrush = Brush.POLY;
    private Brush roadBrush = Brush.POLY;
    private boolean brushDragging;
    private double brushStartX;
    private double brushStartZ;
    private double brushCurrentX;
    private double brushCurrentZ;
    private final List<double[]> brushTrail = new ArrayList<>();

    /** 可滚动列表的滚动位置（键：palette / road / region）——元素多了也能翻。 */
    private final Map<String, Integer> listScroll = new HashMap<>();

    private int scrollOf(String key) {
        return listScroll.getOrDefault(key, 0);
    }

    private void scrollBy(String key, int delta, int max) {
        int next = Math.max(0, Math.min(Math.max(0, max), scrollOf(key) + delta));
        listScroll.put(key, next);
        rebuildButtons();
    }

    /** 鼠标是否悬停在某个可滚动列表上（用于把滚轮给列表而不是缩放）。 */
    private String scrollableListAt(double mouseX, double mouseY) {
        if (!authorMode) {
            return null;
        }
        int panelY = this.height - MARGIN - bottomPanelHeight();
        int contentY = panelY + 28;
        panelContentY = contentY;
        switch (page) {
            case PLACE -> {
                if (mouseX >= MARGIN + 6 && mouseX <= MARGIN + 180
                        && mouseY >= contentY - 2 && mouseY <= contentY + 44) {
                    return "palette";
                }
            }
            case ROAD -> {
                if (mouseX >= MARGIN + 6 && mouseX <= MARGIN + 334
                        && mouseY >= contentY - 2 && mouseY <= contentY + 62) {
                    return "road";
                }
            }
            case REGION -> {
                if (mouseX >= MARGIN + 6 && mouseX <= MARGIN + 334
                        && mouseY >= contentY - 2 && mouseY <= contentY + 77) {
                    return "region";
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** 本帧剩余 blit 预算（防止路网一多就掉帧）。 */
    private int blitBudget;
    private static final int BLIT_BUDGET_PER_FRAME = 6000;
    /** 渲染 LOD 缓存：roadKey|epsilon → 抽稀后的点（按修订号与缩放档失效）。 */
    private final Map<String, List<double[]>> lodCache = new HashMap<>();
    private int lodCacheRevision = Integer.MIN_VALUE;
    private int lodCacheZoomBucket = Integer.MIN_VALUE;
    /** 底部分页面板当前页（放置 / 路网 / 区域）。 */
    private PanelPage page = PanelPage.PLACE;

    /** 当前维度里已解锁的大区域。 */
    private List<AtlasRegion> visibleAreas() {
        List<AtlasRegion> result = new ArrayList<>();
        for (AtlasRegion area : AtlasContent.get().mapAreas(dimension)) {
            if (AtlasUnlocks.isVisible(area.id())) {
                result.add(area);
            }
        }
        return result;
    }

    /** 树形区域列表的展开状态（按区域 id）。 */
    private final java.util.Set<String> expandedAreas = new java.util.HashSet<>();

    /** 树形区域列表的一行。 */
    private record AreaRow(AtlasRegion region, int depth, boolean hasChildren, boolean expanded) {
    }

    /** 大区域为树根、子区域按层级缩进的树形行（点击父行可展开/收起）。 */
    private List<AreaRow> areaRows() {
        List<AreaRow> rows = new ArrayList<>();
        for (AtlasRegion area : treeRoots()) {
            List<AtlasRegion> children = AtlasContent.get().childrenOf(area.id());
            boolean expanded = expandedAreas.contains(area.id());
            rows.add(new AreaRow(area, 0, !children.isEmpty(), expanded));
            if (expanded) {
                for (AtlasRegion child : children) {
                    // 作者要看到并管理未解锁的子区域；玩家只看到已解锁的
                    if (authorMode || AtlasUnlocks.isVisible(child.id())) {
                        rows.add(new AreaRow(child, 1, false, false));
                    }
                }
            }
        }
        return rows;
    }

    /** 树根：作者看到所有顶层区域（含未解锁），玩家只看到已解锁的大区域。 */
    private List<AtlasRegion> treeRoots() {
        if (authorMode) {
            List<AtlasRegion> result = new ArrayList<>();
            for (AtlasRegion region : AtlasContent.get().regionsAt(dimension, viewLayer)) {
                if (!region.isChild()) {
                    result.add(region);
                }
            }
            return result;
        }
        return visibleAreas();
    }

    /** 右上"区域"小节的位置（排在路标面板下面）。 */
    private int regionPanelY() {
        return MARGIN + DISPLAY_PANEL_H + 4 + waypointPanelHeight() + 4;
    }

    private int waypointPanelHeight() {
        if (!waypointPanelOpen) {
            return 18;
        }
        int rows = Math.min(MAX_WAYPOINT_ROWS,
                Math.max(1, AtlasStorage.get(dimension).waypoints().size()));
        return 22 + rows * 15 + (authorMode && selectedWaypoint != null ? 20 : 0);
    }

    private int regionPanelHeight() {
        if (!regionPanelOpen) {
            return 18;
        }
        int rows = Math.max(1, Math.min(MAX_AREA_ROWS, areaRows().size()));
        return 22 + rows * 15;   // 只留"区域"标题 + 列表行（下方描述已按需求去掉）
    }

    /** 选一个中心点：统一入口 + 钳制（P0：所有 centerX/centerZ 写入都要走这里）。 */
    private void setCenter(double x, double z) {
        centerX = x;
        centerZ = z;
        clampCenter();
    }

    /** 把视图钳制在当前大区域内（视野单位）：平移不越界；区域比视野小时居中。 */
    private void clampCenter() {
        AtlasRegion area = viewArea;
        Minecraft client = Minecraft.getInstance();
        if (area == null || client.level == null) {
            return;
        }
        if (!area.dimension().equals(client.level.dimension().location())) {
            return;
        }
        if (viewLayer != area.layer()) {
            viewLayer = area.layer();
        }
        clampZoom();
        if (!viewAreaLocked()) {
            return;   // 作者自由漫游；玩家受"视野单位"限制
        }
        double[] bounds = area.bounds();
        double halfW = this.width / 2.0D / Math.max(0.01D, pixelsPerBlock);
        double halfH = this.height / 2.0D / Math.max(0.01D, pixelsPerBlock);
        double minX = bounds[0] + halfW;
        double maxX = bounds[2] - halfW;
        double minZ = bounds[1] + halfH;
        double maxZ = bounds[3] - halfH;
        centerX = minX > maxX ? (bounds[0] + bounds[2]) / 2.0D : Math.max(minX, Math.min(maxX, centerX));
        centerZ = minZ > maxZ ? (bounds[1] + bounds[3]) / 2.0D : Math.max(minZ, Math.min(maxZ, centerZ));
    }

    /** 让当前大区域刚好完整可见所需的最小缩放（再缩小就会超出"视野单位"）。 */
    private double fitZoom(AtlasRegion area) {
        double[] bounds = area.bounds();
        double worldW = Math.max(1.0D, bounds[2] - bounds[0]);
        double worldH = Math.max(1.0D, bounds[3] - bounds[1]);
        return Math.min(this.width / worldW, this.height / worldH) * 0.85D;
    }

    /** 缩放下限＝刚好装下当前大区域（仅玩家侧）；上限＝样式上限。 */
    private void clampZoom() {
        if (viewArea == null) {
            return;
        }
        double min = viewAreaLocked() ? Math.min(fitZoom(viewArea), AtlasStyle.INSTANCE.zoomMax())
                : AtlasStyle.INSTANCE.zoomMin();
        pixelsPerBlock = Math.max(min, Math.min(AtlasStyle.INSTANCE.zoomMax(), pixelsPerBlock));
    }

    /** 玩家侧是否启用"视野单位"钳制（作者要自由漫游来画东西，不钳制）。 */
    private boolean viewAreaLocked() {
        return !authorMode && viewArea != null;
    }

    /**
     * 当前"视野单位"：玩家所在**大区域**（沿父链上溯到最外层的 mapArea 区域）；
     * 不在任何区域里时取最近的**已解锁**大区域；都没有则 null（自由平移）。
     */
    private void refreshViewArea() {
        AtlasContent content = AtlasContent.get();
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.player != null) {
            ResourceLocation dim = client.level.dimension().location();
            AtlasRegion innermost = content.regionInnermostAt(dim, viewLayer,
                    client.player.getX(), client.player.getZ());
            if (innermost != null) {
                viewArea = outermostMapArea(innermost);
                return;
            }
        }
        AtlasRegion best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AtlasRegion area : content.mapAreas(dimension)) {
            if (!authorMode && !AtlasUnlocks.isVisible(area.id())) {
                continue;
            }
            double[] bounds = area.bounds();
            double cx = (bounds[0] + bounds[2]) / 2.0D;
            double cz = (bounds[1] + bounds[3]) / 2.0D;
            double distance = Math.hypot(cx - centerX, cz - centerZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = area;
            }
        }
        viewArea = best;
    }

    /** 沿父链上溯到最外层的"可移动范围"大区域（视野单位永远是顶层大区域）。 */
    private AtlasRegion outermostMapArea(AtlasRegion region) {
        AtlasRegion current = region;
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (current != null && current.parent() != null && !current.parent().isEmpty()
                && visited.add(current.id())) {
            if (current.mapArea()) {
                break;
            }
            AtlasRegion parent = AtlasContent.get().regionById(current.parent());
            if (parent == null) {
                break;
            }
            current = parent;
        }
        // 上溯到最外层：再往上只要有父且自己是顶层候选，就继续到不能再上
        while (current != null && current.parent() != null && !current.parent().isEmpty()
                && visited.add(current.id())) {
            AtlasRegion parent = AtlasContent.get().regionById(current.parent());
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current;
    }

    /** 切换当前大区域：居中 + 层级跟随。 */
    private void switchViewArea(AtlasRegion area) {
        if(!authorMode)AtlasMapSession.select(area);
        viewArea = area;
        viewLayer = area.layer();
        double[] bounds = area.bounds();
        centerX = (bounds[0] + bounds[2]) / 2.0D;
        centerZ = (bounds[1] + bounds[3]) / 2.0D;
        clampCenter();
        rebuildButtons();
        if (AtlasUnlocks.isVisible(area.id())) {
            message("已切换到区域：" + area.name());
        } else {
            message("「" + area.name() + "」还未解锁（作者预览）");
        }
    }

    private int bottomPanelHeight() {
        if (!authorMode) {
            return 0;
        }
        return switch (page) {
            case PLACE -> 164;
            case ROAD -> 160;
            case REGION -> 166;
        };
    }

    /** 底部操作台的分页：放置素材 / 路网 / 区域树。 */
    private enum PanelPage {
        PLACE("放置"), ROAD("路网"), REGION("区域");

        final String label;

        PanelPage(String label) {
            this.label = label;
        }
    }

    private PanelPage[] visiblePages() {
        // 底部面板只给作者；元素编辑统一走"选中后的信息窗"
        return authorMode
                ? new PanelPage[]{PanelPage.PLACE, PanelPage.ROAD}
                : new PanelPage[]{};
    }

    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && this.font.width(result + "…") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    /** 选中项一致性：避免"节点选中但线路引用失效"这类组合导致渲染/回调出问题。 */
    /** 设置面板里的一个开关按钮（选中态表示"开"）。 */
    private void addToggle(int x, int y, String label, boolean state, Runnable action) {
        AtlasButton button = new AtlasButton(x, y, 152, 16, label + "：" + (state ? "开" : "关"), null, () -> {
            action.run();
            rebuildButtons();
        });
        button.selected = state;
        buttons.add(button);
    }

    /** 左上角设置面板的背景（按钮在上面，见 rebuildButtons）。 */
    private void renderSettingsPanel(GuiGraphics graphics) {
        if (!authorMode || !settingsPanelOpen) {
            return;
        }
        drawNineSlice(graphics, UI_FRAME, MARGIN, MARGIN + 16, 160, 16 + 6 * 18 + 4, 8);
        graphics.drawString(this.font, "全局设置（保存于 biyi_atlas/settings.json）",
                MARGIN + 6, MARGIN + 20, 0xFF586561, false);
    }

    private void normalizeSelection() {        if (selectedNode != null && selectedRoad == null) {
            selectedNode = null;
        }
    }
    private void rebuildButtons() {
        buildingSection=UiSection.WORKFLOW;
        regionPanelOpen=true;waypointPanelOpen=true;
        settingsPanelOpen=drawerOpen&&drawerSection==UiSection.SETTINGS;
        // 作者的区域工作流置于画布顶部，不再埋在底部多页列表中。
        normalizeSelection();
        buttons.clear();
        PanelPage[] pages = visiblePages();
        boolean pageVisible = false;
        for (PanelPage candidate : pages) {
            if (candidate == page) {
                pageVisible = true;
            }
        }
        if (pages.length > 0 && !pageVisible) {
            page = pages[0];
        }

        // ---------- 右上角：显示控制 ----------
        int dispX = this.width - MARGIN - DISPLAY_PANEL_W;
        if(authorMode){
            int barY=MARGIN+58;
            buttons.add(new AtlasButton(MARGIN+8,barY,64,16,"绘制区域",null,()->{tool=Tool.REGION;message("右键加顶点，回车闭合区域");}));
            buttons.add(new AtlasButton(MARGIN+76,barY,64,16,"设地图范围",null,()->{
                if(selectedRegion==null){message("先选择要作为地图范围的区域");return;}
                if(!selectedRegion.mapArea())toggleRegionMapArea(selectedRegion.id());
                message("该区域已作为玩家地图范围");rebuildButtons();
            }));
            buttons.add(new AtlasButton(MARGIN+144,barY,64,16,"发布底图",null,()->{
                message(selectedRegion==null?"先选择一个地图范围区域":AtlasMapSession.publish(selectedRegion.id()));
            }));
            buttons.add(new AtlasButton(MARGIN+8,barY+20,96,16,"一键加载区域",null,()->{
                message(selectedRegion==null?"先选择地图范围区域":AtlasMapSession.prepare(selectedRegion.id()));
            }));
            buttons.add(new AtlasButton(MARGIN+108,barY+20,64,16,"取消准备",null,()->com.biyi.atlas.net.AreaTerrainMessage.cancelPrepare()));
            buttons.add(new AtlasButton(MARGIN+212,barY,64,16,"玩家预览",null,()->{
                if(selectedRegion!=null&&selectedRegion.mapArea()&&AtlasUnlocks.isVisible(selectedRegion.id()))AtlasMapSession.select(selectedRegion);
                Minecraft.getInstance().setScreen(new AtlasScreen(false));
            }));
        }
        if(authorMode){
            buttons.add(new AtlasButton(MARGIN+280,MARGIN+58,108,16,"导出完整地图工程",null,()->{
                ClientEvents.uploadContent();minecraft.player.connection.sendCommand("biyi_atlas project export shared");
                message("导出到 biyi_atlas/projects/shared.biyi.zip；导入会备份旧工程");
            }));
        }
        buildingSection=UiSection.VIEW;
        int row1 = MARGIN + 8;
        int row2 = row1 + 20;
        int row3 = row2 + 20;
        int col1 = dispX + 68;
        int col2 = dispX + 94;
        buttons.add(new AtlasButton(col1, row1, 22, 16, "▲", null, () -> changeViewLayer(1)));
        buttons.add(new AtlasButton(col2, row1, 22, 16, "▼", null, () -> changeViewLayer(-1)));
        buttons.add(new AtlasButton(col1, row2, 22, 16, "◀", null, () -> {
            cycleDimension(-1);
            rebuildButtons();
        }));
        buttons.add(new AtlasButton(col2, row2, 22, 16, "▶", null, () -> {
            cycleDimension(1);
            rebuildButtons();
        }));
        buttons.add(new AtlasButton(col1, row3, 22, 16, "－", null, () -> setZoom(pixelsPerBlock / 1.25D)));
        buttons.add(new AtlasButton(col2, row3, 22, 16, "＋", null, () -> setZoom(pixelsPerBlock * 1.25D)));
        buttons.add(new AtlasButton(col1,row3+20,130,20,"展示成品图："+(AtlasPresentation.enabled()?"优先":"关闭"),null,()->{AtlasPresentation.toggle();rebuildButtons();}));

        buildingSection=UiSection.WAYPOINT;
        // ---------- 右上角（显示面板下方）：路标信息面板，默认折叠 ----------
        int wpY = MARGIN + DISPLAY_PANEL_H + 4;
        buttons.add(new AtlasButton(dispX + 2, wpY + 2, DISPLAY_PANEL_W - 6, 14,
                waypointPanelOpen ? "路标 ▼" : "路标 ▶", null, () -> {
            waypointPanelOpen = !waypointPanelOpen;
            rebuildButtons();
        }));
        if (waypointPanelOpen) {
            List<AtlasWaypoint> waypoints = AtlasStorage.get(dimension).sortedWaypoints();
            int rowY = wpY + 18;
            int shown = 0;
            for (AtlasWaypoint waypoint : waypoints) {
                if (waypoint.layer() != viewLayer) {
                    continue;
                }
                // 路标也要走单一可见性判据（否则"锁区里的路标仍出现在列表里"）
                if (!elementVisible(AtlasVisibility.Kind.WAYPOINT, null, waypoint.x(), waypoint.z())) {
                    continue;
                }
                if (shown++ >= MAX_WAYPOINT_ROWS) {
                    break;
                }
                final AtlasWaypoint target = waypoint;
                boolean pinned = AtlasStorage.get(dimension).isPinned(waypoint);
                AtlasButton row = new AtlasButton(dispX + 2, rowY, DISPLAY_PANEL_W - 26, 14,
                        (pinned ? "★ " : "") + waypoint.name(), null, () -> {
                    selectedWaypoint = target;
                    clearSelectionExcept(target);
                    setCenter(target.x(), target.z());
                    message("已定位：" + target.name());
                    rebuildButtons();
                });
                row.selected = waypoint.equals(selectedWaypoint);
                buttons.add(row);
                buttons.add(new AtlasButton(dispX + DISPLAY_PANEL_W - 22, rowY, 18, 14,
                        pinned ? "★" : "☆", null, () -> {
                    AtlasStorage.get(dimension).togglePin(target);
                    AtlasStorage.markDirty();
                    AtlasStorage.flush();
                    rebuildButtons();
                }));
                rowY += 15;
            }
            if (waypoints.isEmpty()) {
                rowY += 14;
            }
            if (authorMode && selectedWaypoint != null) {
                final AtlasWaypoint target = selectedWaypoint;
                buttons.add(new AtlasButton(dispX + 2, rowY + 2, 70, 16, "传送", null, () -> teleportTo(target)));
                buttons.add(new AtlasButton(dispX + 76, rowY + 2, 70, 16, "编辑信息", null,
                        this::openEditForSelection));
            }
        }

        buildingSection=UiSection.REGIONS;
        // ---------- 右上：区域小节（已解锁的大区域，点选切换可移动范围） ----------
        int areaY = regionPanelY();
        buttons.add(new AtlasButton(dispX + 2, areaY + 2, DISPLAY_PANEL_W - 6, 14,
                regionPanelOpen ? "区域 ▼" : "区域 ▶", null, () -> {
            regionPanelOpen = !regionPanelOpen;
            rebuildButtons();
        }));
        if (regionPanelOpen) {
            // 玩家侧：只列**已解锁的大区域**（视野单位切换器）；作者侧保留树（含子区域）
            List<AreaRow> rows = new ArrayList<>();
            if (authorMode) {
                rows.addAll(areaRows());
            } else {
                for (AtlasRegion area : visibleAreas()) {
                    rows.add(new AreaRow(area, 0, false, false));
                }
            }
            int rowY = areaY + 18;
            int shown = 0;
            for (AreaRow row : rows) {
                if (shown++ >= MAX_AREA_ROWS) {
                    break;
                }
                final AtlasRegion target = row.region();
                boolean current = viewArea != null && viewArea.id().equals(target.id());
                String prefix = row.depth() == 0
                        ? (row.hasChildren() ? (row.expanded() ? "▾ " : "▸ ") : "○ ")
                        : "   └ ";
                String label = prefix + target.name();
                AtlasButton button = new AtlasButton(dispX + 2 + row.depth() * 10, rowY,
                        DISPLAY_PANEL_W - 6 - row.depth() * 10, 14, label, null, () -> {
                    if(authorMode){
                        selectedRegion=target;clearSelectionExcept(target);setLastSelectedRegion(target);
                        lastUiSelection=selectionKey();
                    }
                    if (row.depth() == 0 && row.hasChildren()) {
                        if (expandedAreas.contains(target.id())) {
                            expandedAreas.remove(target.id());
                        } else {
                            expandedAreas.add(target.id());
                        }
                    }
                    switchViewArea(target);
                });
                button.selected = current;
                buttons.add(button);
                rowY += 15;
            }
            if (rows.isEmpty()) {
                rowY += 14;
            }
            // 这个模块是"给玩家看的区域信息"：不在这里放任何作者管理按钮
            // （解锁 / 许可证 / 树管理都在底部「区域」页）
        }

        buildingSection=UiSection.TOOLS;
        // ---------- 底部：功能面板 ----------
        int panelX = MARGIN;
        int panelY = this.height - MARGIN - bottomPanelHeight();
        int tabX = panelX + 8;
        int tabY = panelY + 6;
        for (PanelPage candidate : pages) {
            AtlasButton tab = new AtlasButton(tabX, tabY, 74, 16, candidate.label, null, () -> {
                page = candidate;
                rebuildButtons();
            });
            tab.selected = page == candidate;
            buttons.add(tab);
            tabX += 78;
        }
        int contentY = panelY + 28;
        panelContentY = contentY;

        buildingSection=UiSection.SETTINGS;
        // ---------- 左上角：全局设置（仅作者地图魔杖） ----------
        if (authorMode) {
            buttons.add(new AtlasButton(MARGIN, MARGIN, 56, 14,
                    settingsPanelOpen ? "设置 ▼" : "设置 ▶", null, () -> {
                settingsPanelOpen = !settingsPanelOpen;
                rebuildButtons();
            }));
            if (settingsPanelOpen) {
                int sx = MARGIN + 4;
                int sy = MARGIN + 20;
                addToggle(sx, sy, "战争迷雾贴图", AtlasSettings.fogEnabled(), () -> {
                    AtlasSettings.toggleFog();
                    message("战争迷雾贴图：" + (AtlasSettings.fogEnabled() ? "开" : "关"));
                });
                addToggle(sx, sy + 18, "未解锁封锁道路", AtlasSettings.lockRoads(), () -> {
                    AtlasSettings.toggleLockRoads();
                    message("未解锁封锁道路：" + (AtlasSettings.lockRoads() ? "开" : "关"));
                });
                addToggle(sx, sy + 36, "未解锁封锁路标", AtlasSettings.lockWaypoints(), () -> {
                    AtlasSettings.toggleLockWaypoints();
                    message("未解锁封锁路标：" + (AtlasSettings.lockWaypoints() ? "开" : "关"));
                });
                addToggle(sx, sy + 54, "未解锁封锁地点", AtlasSettings.lockPlaces(), () -> {
                    AtlasSettings.toggleLockPlaces();
                    message("未解锁封锁地点：" + (AtlasSettings.lockPlaces() ? "开" : "关"));
                });
                addToggle(sx, sy + 90, "道路节点给玩家看", AtlasSettings.showNodesToPlayers(), () -> {
                    AtlasSettings.toggleShowNodesToPlayers();
                    message("道路节点给玩家看：" + (AtlasSettings.showNodesToPlayers() ? "开" : "关"));
                    rebuildButtons();
                });
                buttons.add(new AtlasButton(sx,sy+108,152,20,"子区域锁定隐藏："+(AtlasContent.get().allChildLocksContent()?"开":"关"),null,()->{
                    boolean next=!AtlasContent.get().allChildLocksContent();
                    int changed=AtlasContent.get().setChildLocksContent(next);
                    AtlasContent.get().saveAuthored();
                    message("已更新 "+changed+" 个子区域的锁定显示规则");rebuildButtons();
                }));
            }
        }

        buildingSection=UiSection.TOOLS;
        if (pages.length == 0) {
            // 玩家没有底部作者面板，只有"选中元素信息窗"
            addSelectionButtons();
            arrangeUi();
            return;
        }
        switch (page) {
            case PLACE -> {
                // 素材：预设 + 自定义图标（每行 8 个，最多 16 个）
                List<String> paletteNames = new ArrayList<>(PLACEABLE_ICONS);
                for (String custom : CustomIcons.names()) {
                    paletteNames.add(custom);
                }
                int paletteRow = 0;
                for (int i = paletteRow * 8; i < paletteNames.size(); i++) {
                    String icon = paletteNames.get(i);
                    int slot = i - paletteRow * 8;
                    AtlasButton button = new AtlasButton(panelX + 8 + (slot % 8) * 21,
                            contentY + (slot / 8) * 21, 20, 20, icon, iconTexture(icon), () -> {
                        brushIcon = icon;
                        tool = Tool.PLACE;
                        message("已选素材 " + icon + "，点地图放置");
                        rebuildButtons();
                    });
                    button.selected = tool == Tool.PLACE && icon.equals(brushIcon);
                    buttons.add(button);
                }
                buttons.add(toolButton(panelX + 182, contentY, 76, "选择", Tool.SELECT));
                buttons.add(toolButton(panelX + 262, contentY, 70, "画道路", Tool.ROAD));
                buttons.add(toolButton(panelX + 182, contentY + 20, 76, "画区域", Tool.REGION));
                buttons.add(toolButton(panelX + 262, contentY + 20, 70, "放传送点", Tool.TELEPORT));
                // 导入与参考（不再有"生成俯视图"，解锁相关也不属于"放置"范畴）
                buttons.add(new AtlasButton(panelX + 8, contentY + 48, 118, 16, "选标记图…", null, () -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new AtlasImagePickerScreen(this,
                                AtlasImagePickerScreen.Purpose.MARKER_ICON));
                    }
                }));
                buttons.add(new AtlasButton(panelX + 8, contentY + 68, 118, 16, "扫描附近NPC", null, () -> {
                    message(NpcScanner.scan(128));
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 130, contentY + 68, 202, 16,
                        "旅行图底片：" + (journeyUnderlay ? "开" : "关") + "（" + JourneyMapImport.layer() + "）",
                        null, () -> {
                    journeyUnderlay = !journeyUnderlay;
                    if (journeyUnderlay && JourneyMapImport.currentLayerDir() == null) {
                        message("找不到旅行地图数据：" + JourneyMapImport.diagnostic());
                    } else {
                        message("旅行图底片：" + (journeyUnderlay ? "开（画区域时可参考）" : "关"));
                    }
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 8, contentY + 88, 118, 16, "换底片图层", null, () -> {
                    JourneyMapImport.cycleLayer();
                    message("底片图层：" + JourneyMapImport.layer());
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 130, contentY + 88, 202, 16, "移除最近导入的标记图", null, () -> {
                    message(CustomIcons.removeLast());
                    rebuildButtons();
                }));
                // 笔刷：区域圆形/矩形、道路直线/手绘（右键按下拖拽成形，松手生成）
                if (tool == Tool.REGION) {
                    buttons.add(brushButton(panelX + 8, 76, Brush.POLY, true));
                    buttons.add(brushButton(panelX + 88, 76, Brush.FREEHAND, true));
                    buttons.add(brushButton(panelX + 168, 76, Brush.CIRCLE, true));
                    buttons.add(brushButton(panelX + 248, 84, Brush.RECT, true));
                } else if (tool == Tool.ROAD) {
                    buttons.add(brushButton(panelX + 8, 106, Brush.POLY, false));
                    buttons.add(brushButton(panelX + 118, 106, Brush.FREEHAND, false));
                    buttons.add(brushButton(panelX + 228, 104, Brush.LINE, false));
                } else {
                    buttons.add(new AtlasButton(panelX + 8, contentY + 108, 324, 16,
                            "笔刷：选「画区域」或「画道路」后可切换圆形/矩形/直线/手绘", null, () -> {
                        message("笔刷：先选「画区域」（圆形/矩形/手绘）或「画道路」（直线/手绘）");
                    }));
                }
            }
            case ROAD -> {
                // 路网：按数据结构列出所有路（点击＝选中并居中）
                List<AtlasRoad> roadList = AtlasContent.get().roadsAt(dimension, viewLayer);
                int roadRowY = contentY;
                int roadShown = 0;
                int roadStart = scrollOf("road");
                for (int roadIndex = roadStart;
                        roadIndex < roadList.size() && roadIndex < roadStart + 4; roadIndex++) {
                    AtlasRoad road = roadList.get(roadIndex);
                    if (roadShown++ >= 4) {
                        break;
                    }
                    final AtlasRoad target = road;
                    String label = road.name()
                            + " · 顶点 " + road.points().size()
                            + " · 节点 " + road.nodes().size()
                            + (road.branchOf().isEmpty() ? "" : " · 支线←" + road.branchOf())
                            + (road.region().isEmpty() ? "" : " · " + road.region());
                    AtlasButton button = new AtlasButton(panelX + 8, roadRowY, 324, 15,
                            trimToWidth(label, 308), null, () -> {
                        selectedRoad = target;
                        clearSelectionExcept(target);
                        if (!target.points().isEmpty()) {
                            double[] middle = target.points().get(target.points().size() / 2);
                            setCenter(middle[0], middle[1]);
                        }
                        rebuildButtons();
                        message("已选中线路：" + target.name() + "（节点 " + target.nodes().size() + "）");
                    });
                    button.selected = target.equals(selectedRoad);
                    buttons.add(button);
                    roadRowY += 15;
                }
                if (roadList.isEmpty()) {
                    roadRowY += 15;
                }
                buttons.add(new AtlasButton(panelX + 312, contentY, 20, 14, "上一条", null, () -> scrollBy("road", -1, Math.max(0,roadList.size()-4))));
                buttons.add(new AtlasButton(panelX + 312, contentY + 15, 20, 14, "下一条", null, () -> scrollBy("road", 1, Math.max(0,roadList.size()-4))));
                buttons.add(new AtlasButton(panelX + 8, contentY + 66, 158, 16, "记录新路（R 结束）", null, () -> {
                    AtlasRoadRecorder.start();
                    if (minecraft != null) {
                        minecraft.setScreen(null);
                    }
                }));
                buttons.add(new AtlasButton(panelX + 170, contentY + 66, 162, 16, "体检+一致性", null, () -> {
                    List<String> issues = AtlasContent.get().checkRoads(dimension, viewLayer);
                    com.biyi.atlas.map.AtlasValidator.Report report =
                            com.biyi.atlas.map.AtlasValidator.run();
                    StringBuilder text = new StringBuilder();
                    text.append("道路 ").append(issues.size()).append(" 项");
                    text.append(" · 一致性 ").append(report.summary());
                    if (!issues.isEmpty()) {
                        text.append("｜").append(issues.get(0));
                    }
                    message(text.toString());
                    if (minecraft != null) {
                        minecraft.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "[避役图志] 一致性明细可用 /biyi_atlas_local verify 查看"), false);
                    }
                }));
                buttons.add(new AtlasButton(panelX + 8, contentY + 86, 158, 16, "缝合端点(≤2格)", null, () -> {
                    int merged = AtlasContent.get().stitchCloseNodes(dimension, viewLayer, 2.0D);
                    AtlasContent.get().saveAuthored();
                    message(merged == 0 ? "没有需要缝合的端点" : "已缝合 " + merged + " 处端点");
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 170, contentY + 86, 162, 16,
                        "共 " + roadList.size() + " 条 · 完整列表", null, () -> {
                    if (roadList.isEmpty()) {
                        message("这一层还没有道路");
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    for (AtlasRoad road : roadList) {
                        names.add(road.name() + "(" + road.points().size() + "点/" + road.nodes().size() + "节点)");
                    }
                    message("路网：" + String.join("、", names));
                }));
                // 几何工具：把"一堆折线"变成一张连通的图
                buttons.add(new AtlasButton(panelX + 8, contentY + 106, 104, 16, "生成交叉岔口", null, () -> {
                    int added = com.biyi.atlas.map.RoadGeometry.generateJunctions(dimension, viewLayer, 2.0D);
                    message(added == 0 ? "没有发现新的交叉点（或已生成过）"
                            : "已在交叉处生成 " + added + " 个共享岔口节点");
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 116, contentY + 106, 104, 16, "吸附端点(≤3格)", null, () -> {
                    int snapped = com.biyi.atlas.map.RoadGeometry.snapEndpoints(dimension, viewLayer, 3.0D);
                    message(snapped == 0 ? "没有可吸附的端点" : "已吸附 " + snapped + " 处端点（接驳/合并）");
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 224, contentY + 106, 108, 16, "简化折线(0.5格)", null, () -> {
                    int removed = com.biyi.atlas.map.RoadGeometry.simplifyAll(dimension, viewLayer, 0.5D);
                    message(removed == 0 ? "没有需要简化的顶点" : "已抽稀 " + removed + " 个顶点（受保护顶点保留）");
                    rebuildButtons();
                }));
            }
            case REGION -> {
                // 区域：按父子关系的"文件树"呈现
                List<AreaRow> regionRows = areaRows();
                int regionRowY = contentY;
                int regionShown = 0;
                int regionStart = scrollOf("region");
                for (int regionIndex = regionStart;
                        regionIndex < regionRows.size() && regionIndex < regionStart + 5; regionIndex++) {
                    AreaRow row = regionRows.get(regionIndex);
                    if (regionShown++ >= 5) {
                        break;
                    }
                    final AtlasRegion target = row.region();
                    String prefix = row.depth() == 0
                            ? (row.hasChildren() ? (row.expanded() ? "▾ " : "▸ ") : "○ ")
                            : "   └ ";
                    boolean unlocked = AtlasUnlocks.isVisible(target.id());
                    String label = prefix + (unlocked ? "✔ " : "✖ ") + target.name()
                            + (target.mapArea() ? "（大区域）" : "");
                    AtlasButton button = new AtlasButton(panelX + 8 + row.depth() * 12, regionRowY,
                            324 - row.depth() * 12, 15, trimToWidth(label, 300), null, () -> {
                        if (row.depth() == 0 && row.hasChildren()) {
                            if (expandedAreas.contains(target.id())) {
                                expandedAreas.remove(target.id());
                            } else {
                                expandedAreas.add(target.id());
                            }
                        }
                        selectedRegion = target;
                        setLastSelectedRegion(target);
                        clearSelectionExcept(target);
                        rebuildButtons();
                    });
                    button.selected = target.equals(selectedRegion);
                    buttons.add(button);
                    regionRowY += 15;
                }
                if (regionRows.isEmpty()) {
                    regionRowY += 15;
                }
                buttons.add(new AtlasButton(panelX + 312, contentY, 20, 14, "上一项", null, () -> scrollBy("region", -1, Math.max(0,regionRows.size()-5))));
                buttons.add(new AtlasButton(panelX + 312, contentY + 15, 20, 14, "下一项", null, () -> scrollBy("region", 1, Math.max(0,regionRows.size()-5))));
                buttons.add(new AtlasButton(panelX + 8, contentY + 82, 104, 16,
                        selectedRegion != null && AtlasUnlocks.isUnlocked(selectedRegion.id())
                                ? "锁定区域" : "解锁区域", null, () -> {
                    if (selectedRegion == null) {
                        message("先在树里点一个区域");
                        return;
                    }
                    AtlasUnlocks.toggle(selectedRegion.id());
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(panelX + 116, contentY + 82, 104, 16, "生成许可证", null, () -> {
                    if (selectedRegion == null) {
                        message("先在树里点一个区域");
                        return;
                    }
                    requestLicense(selectedRegion.id());
                }));
                buttons.add(new AtlasButton(panelX + 224, contentY + 82, 108, 16, "编辑该区域", null, () -> {
                    if (selectedRegion == null) {
                        message("先在树里点一个区域");
                        return;
                    }
                    openEditForSelection();
                }));
                // 分层锁批量开关：未解锁区域（含父已解锁的子区域）是否隐藏其内部内容
                buttons.add(new AtlasButton(panelX + 8, contentY + 104, 324, 16,
                        "批量：子区域锁定隐藏内容 " + (AtlasContent.get().allChildLocksContent()
                                ? "开（点=全部关闭）" : "关（点=全部开启）"), null, () -> {
                    boolean next = !AtlasContent.get().allChildLocksContent();
                    int changed = AtlasContent.get().setChildLocksContent(next);
                    AtlasContent.get().saveAuthored();
                    message("已把 " + changed + " 个区域改为\"" + (next ? "锁定即隐藏内容" : "锁定不隐藏内容") + "\"");
                    rebuildButtons();
                }));
            }
        }
        // 关键：作者侧也要加"信息窗按钮"（曾经只在玩家分支调用 → 作者信息窗只剩文字、没有编辑按钮）
        addSelectionButtons();
        if(authorMode){
            buildingSection=UiSection.TOOLS;
            buttons.add(new AtlasButton(0,0,132,18,"撤销编辑 Ctrl+Z",null,()->moveEditHistory(false)));
            buttons.add(new AtlasButton(136,0,132,18,"重做编辑 Ctrl+Y",null,()->moveEditHistory(true)));
            buildingSection=UiSection.WAYPOINT;
            buttons.add(new AtlasButton(0,0,132,18,"撤销路标删除",null,()->{
                try{
                    if(!AtlasAuthoring.allowed())return;
                    if(!AtlasStorage.get(dimension).restoreDeletedWaypoint()){message("当前维度没有可恢复的路标");return;}
                    AtlasStorage.markDirty();AtlasStorage.flush();rebuildButtons();message("已恢复最近删除的路标");
                }catch(Exception e){message(e.getMessage());}
            }));
        }
        arrangeUi();
    }

    private void moveEditHistory(boolean redo){
        if(!authorMode||!AtlasAuthoring.allowed())return;
        if(!pendingRoad.isEmpty()||!pendingRegion.isEmpty()){
            message("请先完成或取消正在绘制的线条/区域，再撤销编辑");return;
        }
        try{
            if(!AtlasContent.get().moveEditHistory(redo)){message(redo?"没有可重做的编辑":"没有可撤销的编辑");return;}
            clearSelection();setLastSelectedRegion(null);viewArea=null;
            AtlasMapSession.reset();refreshViewArea();rebuildButtons();
            message(redo?"已重做编辑":"已撤销编辑（区域归属及子区域一并恢复）");
        }catch(Exception e){message("撤销/重做失败："+e.getMessage());}
    }

    private void clearSelection() {
        selectedPlace = null;
        selectedRoad = null;
        selectedRegion = null;
        selectedWaypoint = null;
        selectedImage = null;
        selectedNode = null;
    }

    private void clearSelectionExcept(Object keep) {
        if (keep != selectedPlace) {
            selectedPlace = null;
        }
        if (keep != selectedRoad) {
            selectedRoad = null;
        }
        if (keep != selectedRegion) {
            selectedRegion = null;
        }
        if (keep != selectedWaypoint) {
            selectedWaypoint = null;
        }
        if (keep != selectedImage) {
            selectedImage = null;
        }
        if (keep != selectedNode) {
            selectedNode = null;
        }
    }

    /** 打开"编辑信息"界面：改名字、改描述。 */
    private void openEditForSelection() {
        // 只有作者地图魔杖打开的地图才能编辑（玩家侧完全隐藏）
        if (!authorMode) {
            return;
        }
        if (selectedImage != null) {
            AtlasMapImage target = selectedImage;
            minecraft.setScreen(new AtlasEditScreen(this, "编辑地点图",
                    List.of("像素 " + target.width() + "×" + target.height(),
                            "左上角 X " + (int) target.originX() + "  Z " + (int) target.originZ()),
                    List.of(new AtlasEditScreen.TextInput("名称", target.name()),
                            new AtlasEditScreen.TextInput("描述", target.description())),
                    List.of(
                            new AtlasEditScreen.Cycle("层级",
                                    () -> String.valueOf(currentImage(target.id()).layer()),
                                    () -> cycleImageLayer(target.id())),
                            new AtlasEditScreen.Cycle("每像素方块",
                                    () -> String.format("%.1f", currentImage(target.id()).blocksPerPixel()),
                                    () -> cycleImageScale(target.id())),
                            new AtlasEditScreen.Cycle("透明度",
                                    () -> String.valueOf(currentImage(target.id()).alpha()),
                                    () -> cycleImageAlpha(target.id()))),
                    values -> updateImage(target.id(), values.get("名称"), values.get("描述"))));
            return;
        }
        if (selectedWaypoint != null) {
            AtlasWaypoint target = selectedWaypoint;
            minecraft.setScreen(new AtlasEditScreen(this, "编辑路标",
                    List.of("X " + (int) target.x() + "  Z " + (int) target.z()),
                    List.of(new AtlasEditScreen.TextInput("名称", target.name()),
                            new AtlasEditScreen.TextInput("描述", target.description())),
                    List.of(
                            new AtlasEditScreen.Cycle("类型",
                                    () -> currentWaypoint(target).waypointType().label(),
                                    () -> cycleWaypointType(target)),
                            new AtlasEditScreen.Cycle("层级",
                                    () -> String.valueOf(currentWaypoint(target).layer()),
                                    () -> cycleWaypointLayer(target))),
                    values -> updateWaypoint(target, values.get("名称"), values.get("描述"))));
            return;
        }
        if (selectedPlace != null) {
            AtlasPlace target = selectedPlace;
            minecraft.setScreen(new AtlasEditScreen(this, "编辑地点",
                    List.of("X " + (int) target.x() + "  Z " + (int) target.z()),
                    List.of(new AtlasEditScreen.TextInput("名称", target.name()),
                            new AtlasEditScreen.TextInput("描述", target.description())),
                    List.of(
                            new AtlasEditScreen.Cycle("图标",
                                    () -> currentPlace(target).icon(),
                                    () -> cyclePlaceIcon(target)),
                            new AtlasEditScreen.Cycle("层级",
                                    () -> String.valueOf(currentPlace(target).layer()),
                                    () -> moveSelectionLayer(1)),
                            new AtlasEditScreen.Cycle("所属区域",
                                    () -> {
                                        String region = currentPlace(target).region();
                                        return region.isEmpty() ? "（无）" : region;
                                    },
                                    () -> cyclePlaceRegion(target)),
                            new AtlasEditScreen.Cycle("常显名字",
                                    () -> currentPlace(target).alwaysLabel() ? "是" : "否",
                                    () -> togglePlaceLabel(target))),
                    values -> {
                        AtlasPlace current = currentPlace(target);
                        replacePlace(current, new AtlasPlace(values.get("名称"), current.type(),
                                current.dimension(), current.layer(), current.region(), current.x(),
                                current.z(), current.icon(), current.rgb(), values.get("描述"),
                                current.alwaysLabel(), current.interactive()));
                    }));
            return;
        }
        if (selectedRoad != null) {
            AtlasRoad target = selectedRoad;
            minecraft.setScreen(new AtlasEditScreen(this, "编辑道路",
                    List.of("顶点 " + target.points().size() + " 个"),
                    List.of(new AtlasEditScreen.TextInput("名称", target.name())),
                    List.of(
                            new AtlasEditScreen.Cycle("层级",
                                    () -> String.valueOf(currentRoad(target).layer()),
                                    () -> cycleRoadLayer(target)),
                            new AtlasEditScreen.Cycle("宽度",
                                    () -> String.valueOf(currentRoad(target).width()),
                                    () -> cycleRoadWidth(target)),
                            new AtlasEditScreen.Cycle("线型",
                                    () -> currentRoad(target).dashed() ? "虚线" : "实线",
                                    () -> toggleRoadDashed(target))),
                    values -> {
                        AtlasRoad current = currentRoad(target);
                        replaceRoad(current, current.withName(values.get("名称")));
                    }));
            return;
        }
        if (selectedRegion != null) {
            if(selectedRegion.mapArea()&&(AtlasMapSession.active()==null||!AtlasMapSession.active().id().equals(selectedRegion.id())))AtlasMapSession.select(selectedRegion);
            AtlasRegion target = selectedRegion;
            minecraft.setScreen(new AtlasEditScreen(this, "编辑区域",
                    List.of("边界 " + target.outline().size() + " 顶点"),
                    List.of(new AtlasEditScreen.TextInput("名称", target.name()),
                            new AtlasEditScreen.TextInput("标注文字", target.labelText()),
                            new AtlasEditScreen.TextInput("许可证 id", target.unlockId())),
                    List.of(
                            new AtlasEditScreen.Cycle("层级",
                                    () -> String.valueOf(currentRegion(target.id()).layer()),
                                    () -> cycleRegionLayer(target.id(), 1)),
                            new AtlasEditScreen.Cycle("父区域",
                                    () -> {
                                        String parent = currentRegion(target.id()).parent();
                                        if (parent == null || parent.isEmpty()) {
                                            return "（无 · 顶层）";
                                        }
                                        AtlasRegion p = AtlasContent.get().regionById(parent);
                                        return p == null ? parent : p.name();
                                    },
                                    () -> cycleRegionParent(target.id())),
                            new AtlasEditScreen.Cycle("成品展示方案（大区域）",
                                    () -> AtlasPresentation.choiceLabel(target.id()),
                                    () -> {if(currentRegion(target.id()).mapArea())AtlasPresentation.cycle(target.id());}),
                            new AtlasEditScreen.Cycle("地图范围单位",
                                    () -> currentRegion(target.id()).mapArea() ? "是" : "否",
                                    () -> toggleRegionMapArea(target.id())),
                            new AtlasEditScreen.Cycle("锁定子区域内容",
                                    () -> currentRegion(target.id()).childLocksContent() ? "是" : "否",
                                    () -> toggleRegionChildLocks(target.id())),
                            new AtlasEditScreen.Cycle("未解锁示边界",
                                    () -> currentRegion(target.id()).showOutlineWhenLocked() ? "是" : "否",
                                    () -> toggleRegionOutline(target.id())),
                            new AtlasEditScreen.Cycle("默认解锁",
                                    () -> currentRegion(target.id()).defaultUnlocked() ? "是" : "否",
                                    () -> toggleRegionDefault(target.id()))),
                    values -> {
                        AtlasRegion current = currentRegion(target.id());
                        applyRegion(withRegion(current, values.get("名称"), current.labelText(),
                                values.get("许可证 id"), current.image()));
                        // 标注文字单独处理（第三个输入框是许可证 id）
                        AtlasRegion applied = currentRegion(target.id());
                        applyRegion(withRegion(applied, applied.name(), values.get("标注文字"),
                                applied.unlockId(), applied.image()));
                    }));
            return;
        }
        message("先在地图上点选一个元素");
    }

    // ---------------------------------------------------------------- 元素读写助手

    private AtlasRegion currentRegion(String id) {
        AtlasRegion region = AtlasContent.get().regionById(id);
        return region == null ? selectedRegion : region;
    }

    private AtlasPlace currentPlace(AtlasPlace place) {
        AtlasPlace current = AtlasContent.get().placeByKey(place.key());
        return current == null ? place : current;
    }

    private AtlasRoad currentRoad(AtlasRoad road) {
        if (road == null) {
            return null;
        }
        // 按 key（= 稳定 id）查回；只有旧数据没有 id 时才退回按名字
        AtlasRoad byKey = AtlasContent.get().roadByKey(road.key());
        if (byKey != null) {
            return byKey;
        }
        AtlasRoad byName = AtlasContent.get().roadByName(road.name());
        return byName == null ? road : byName;
    }

    private AtlasWaypoint currentWaypoint(AtlasWaypoint waypoint) {
        AtlasWaypoint current = AtlasStorage.get(dimension).findWaypoint(waypoint);
        return current == null ? waypoint : current;
    }

    private AtlasMapImage currentImage(String id) {
        for (AtlasMapImage image : AtlasContent.get().mapsAt(dimension, viewLayer)) {
            if (image.id().equals(id)) {
                return image;
            }
        }
        return selectedImage;
    }

    private AtlasRegion withRegion(AtlasRegion region, String name, String labelText, String unlockId,
                                   String image) {
        return region.withBasic(name, labelText, unlockId, image);
    }

    private void applyRegion(AtlasRegion region) {
        AtlasContent.get().replaceRegion(region);
        AtlasContent.get().saveAuthored();
        if (selectedRegion != null && selectedRegion.id().equals(region.id())) {
            selectedRegion = region;
        }
        setLastSelectedRegion(region);
        rebuildButtons();
    }

    private void replacePlace(AtlasPlace oldPlace, AtlasPlace newPlace) {
        AtlasContent.get().removePlace(oldPlace);
        AtlasContent.get().addPlace(newPlace);
        selectedPlace = newPlace;
        AtlasContent.get().saveAuthored();
        rebuildButtons();
    }

    private void replaceRoad(AtlasRoad oldRoad, AtlasRoad newRoad) {
        AtlasContent.get().removeRoad(oldRoad);
        AtlasContent.get().addRoad(newRoad);
        selectedRoad = newRoad;
        AtlasContent.get().saveAuthored();
        rebuildButtons();
    }

    private void updateWaypoint(AtlasWaypoint oldWaypoint, String name, String description) {
        AtlasWaypoint updated = new AtlasWaypoint(name, oldWaypoint.type(), oldWaypoint.layer(),
                oldWaypoint.x(), oldWaypoint.z(), oldWaypoint.icon(), description);
        AtlasStorage.get(dimension).replaceWaypoint(oldWaypoint, updated);
        selectedWaypoint = updated;
        AtlasStorage.markDirty();
        AtlasStorage.flush();
        rebuildButtons();
        message("已保存路标信息");
    }

    private void updateImage(String id, String name, String description) {
        AtlasMapImage target = currentImage(id);
        AtlasContent.get().addMapImage(new AtlasMapImage(target.id(), target.dimension(), target.layer(),
                target.region(), target.texture(), target.originX(), target.originZ(), target.width(),
                target.height(), target.blocksPerPixel(), target.alpha(), name, description));
        AtlasContent.get().saveAuthored();
        selectedImage = currentImage(id);
        rebuildButtons();
        message("已保存地点图信息");
    }

    private String imageLabel(String path) {
        if (path == null || path.isEmpty()) {
            return "（无）";
        }
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private void cycleRegionLayer(String id, int delta) {
        AtlasRegion region = currentRegion(id);
        if (region.isChild() || !AtlasContent.get().childrenOf(id).isEmpty()) {
            message("有父子关系的区域不能单独换层（先解除父区域）");
            return;
        }
        applyRegion(region.withLayer(region.layer() + delta));
    }

    private void cycleRegionImage(String id) {
        AtlasRegion region = currentRegion(id);
        List<String> options = new ArrayList<>();
        options.add("");
        for (AtlasMapImage image : AtlasContent.get().mapsAt(dimension, viewLayer)) {
            options.add(image.texture().toString());
        }
        int index = options.indexOf(region.image());
        String next = options.get((index + 1) % options.size());
        applyRegion(withRegion(region, region.name(), region.labelText(), region.unlockId(), next));
        message("区域映像图：" + imageLabel(next));
    }

    private void toggleRegionOutline(String id) {
        AtlasRegion region = currentRegion(id);
        applyRegion(region.withFlags(!region.showOutlineWhenLocked(), region.defaultUnlocked()));
    }

    /** 父区域循环：（无/顶层）→ 同维度同层、且不会成环、且不重叠的顶层大区域。 */
    private void cycleRegionParent(String id) {
        AtlasRegion region = currentRegion(id);
        List<String> options = new ArrayList<>();
        options.add("");
        for (AtlasRegion candidate : AtlasContent.get().regionsAt(dimension, region.layer())) {
            if (candidate.id().equals(id) || candidate.isChild()) {
                continue;
            }
            if (AtlasContent.get().wouldCreateCycle(id, candidate.id())) {
                continue;
            }
            options.add(candidate.id());
        }
        String current = region.parent() == null ? "" : region.parent();
        int index = options.indexOf(current);
        String next = options.get((index + 1) % options.size());
        if (!next.isEmpty()) {
            // 设为子区域后必须真的落在父区域内（否则给出提示但不阻止，作者可能先画后调）
            AtlasRegion parent = AtlasContent.get().regionById(next);
            if (parent != null && !parent.contains(region.labelX(), region.labelZ())) {
                message("提示：该区域中心不在「" + parent.name() + "」内，稍后调整边界");
            }
            if (parent != null && !parent.mapArea()) {
                message("提示：父区域「" + parent.name() + "」当前不是地图范围单位，可顺手打开");
            }
        }
        applyRegion(region.withOwner(next, region.mapArea(),
                next.isEmpty() ? false : region.childLocksContent()));
    }

    private void toggleRegionMapArea(String id) {
        AtlasRegion region = currentRegion(id);
        if (region.isChild() && !region.mapArea()) {
            message("子区域一般不做地图范围单位；如确需请先解除父区域");
        }
        applyRegion(region.withOwner(region.parent(), !region.mapArea(), region.childLocksContent()));
    }

    private void toggleRegionChildLocks(String id) {
        AtlasRegion region = currentRegion(id);
        applyRegion(region.withOwner(region.parent(), region.mapArea(), !region.childLocksContent()));
    }

    private void toggleRegionDefault(String id) {
        AtlasRegion region = currentRegion(id);
        applyRegion(region.withFlags(region.showOutlineWhenLocked(), !region.defaultUnlocked()));
    }

    private void cyclePlaceIcon(AtlasPlace place) {
        AtlasPlace current = currentPlace(place);
        int index = PLACEABLE_ICONS.indexOf(current.icon());
        String next = PLACEABLE_ICONS.get((index + 1) % PLACEABLE_ICONS.size());
        replacePlace(current, new AtlasPlace(current.name(), current.type(), current.dimension(),
                current.layer(), current.region(), current.x(), current.z(), next, current.rgb(),
                current.description(), current.alwaysLabel(), current.interactive()));
    }

    private void cyclePlaceRegion(AtlasPlace place) {
        AtlasPlace current = currentPlace(place);
        List<String> options = new ArrayList<>();
        options.add("");
        for (AtlasRegion region : AtlasContent.get().regionsAt(dimension, current.layer())) {
            options.add(region.id());
        }
        int index = options.indexOf(current.region());
        String next = options.get((index + 1) % options.size());
        replacePlace(current, new AtlasPlace(current.name(), current.type(), current.dimension(),
                current.layer(), next, current.x(), current.z(), current.icon(), current.rgb(),
                current.description(), current.alwaysLabel(), current.interactive()));
    }

    private void togglePlaceLabel(AtlasPlace place) {
        AtlasPlace current = currentPlace(place);
        replacePlace(current, new AtlasPlace(current.name(), current.type(), current.dimension(),
                current.layer(), current.region(), current.x(), current.z(), current.icon(), current.rgb(),
                current.description(), !current.alwaysLabel(), current.interactive()));
    }

    private void cycleWaypointType(AtlasWaypoint waypoint) {
        AtlasWaypoint current = currentWaypoint(waypoint);
        WaypointType next = current.waypointType().next();
        AtlasWaypoint updated = new AtlasWaypoint(current.name(), next.name(), current.layer(), current.x(),
                current.z(), next.icon(), current.description());
        AtlasStorage.get(dimension).replaceWaypoint(current, updated);
        selectedWaypoint = updated;
        AtlasStorage.markDirty();
        AtlasStorage.flush();
    }

    private void cycleWaypointLayer(AtlasWaypoint waypoint) {
        AtlasWaypoint current = currentWaypoint(waypoint);
        AtlasWaypoint updated = new AtlasWaypoint(current.name(), current.type(), current.layer() + 1,
                current.x(), current.z(), current.icon(), current.description());
        AtlasStorage.get(dimension).replaceWaypoint(current, updated);
        selectedWaypoint = updated;
        AtlasStorage.markDirty();
        AtlasStorage.flush();
    }

    private void cycleRoadLayer(AtlasRoad road) {
        AtlasRoad current = currentRoad(road);
        replaceRoad(current, current.withLayer(current.layer() + 1));
    }

    private void cycleRoadWidth(AtlasRoad road) {
        AtlasRoad current = currentRoad(road);
        int next = current.width() >= 8 ? 2 : current.width() + 2;
        replaceRoad(current, current.withWidth(next));
    }

    private void toggleRoadDashed(AtlasRoad road) {
        AtlasRoad current = currentRoad(road);
        replaceRoad(current, current.withDashed(!current.dashed()));
    }

    private void cycleImageLayer(String id) {
        AtlasMapImage target = currentImage(id);
        AtlasContent.get().addMapImage(new AtlasMapImage(target.id(), target.dimension(),
                target.layer() + 1, target.region(), target.texture(), target.originX(), target.originZ(),
                target.width(), target.height(), target.blocksPerPixel(), target.alpha(), target.name(),
                target.description()));
        AtlasContent.get().saveAuthored();
        selectedImage = currentImage(id);
    }

    private void cycleImageScale(String id) {
        AtlasMapImage target = currentImage(id);
        double next = target.blocksPerPixel() >= 16 ? 1 : target.blocksPerPixel() * 2;
        AtlasContent.get().addMapImage(new AtlasMapImage(target.id(), target.dimension(), target.layer(),
                target.region(), target.texture(), target.originX(), target.originZ(), target.width(),
                target.height(), next, target.alpha(), target.name(), target.description()));
        AtlasContent.get().saveAuthored();
        selectedImage = currentImage(id);
    }

    private void cycleImageAlpha(String id) {
        AtlasMapImage target = currentImage(id);
        int next = target.alpha() >= 255 ? 96 : Math.min(255, target.alpha() + 64);
        AtlasContent.get().addMapImage(new AtlasMapImage(target.id(), target.dimension(), target.layer(),
                target.region(), target.texture(), target.originX(), target.originZ(), target.width(),
                target.height(), target.blocksPerPixel(), next, target.name(), target.description()));
        AtlasContent.get().saveAuthored();
        selectedImage = currentImage(id);
    }

    /** 把选中的图片文件挂到区域上作为"区域映像图"。 */
    private void attachRegionImage(String regionId, java.nio.file.Path file) {
        String base = file.getFileName().toString().replaceAll("(?i)\\.(png|jpg|jpeg)$", "");
        ResourceLocation texture = new ResourceLocation(BiyiAtlas.MODID,
                "textures/generated/region_" + LocationCapture.safeName(base) + ".png");
        java.nio.file.Path copy = LocationCapture.locationDir()
                .resolve("region_" + LocationCapture.safeName(base) + ".png");
        try {
            java.nio.file.Files.createDirectories(copy.getParent());
            java.nio.file.Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LocationCapture.register(texture, copy);
        } catch (java.io.IOException e) {
            message("复制图片失败：" + e.getMessage());
            return;
        }
        AtlasRegion region = currentRegion(regionId);
        applyRegion(withRegion(region, region.name(), region.labelText(), region.unlockId(),
                texture.toString()));
        message("区域映像图已设为：" + base);
    }

    /** 生成一张绑定该区域的许可证（由服务端发放）。 */
    private void requestLicense(String regionId) {
        AtlasNetwork.sendGiveLicense(regionId);
        message("已请求生成许可证：" + regionId);
    }

    /** 传送到路标（服务端校验并落点）。 */
    private void teleportTo(AtlasWaypoint waypoint) {
        AtlasNetwork.sendTeleportRequest(dimension, waypoint.x(), waypoint.z());
        message("正在传送到：" + waypoint.name());
    }

    private void moveSelectionLayer(int delta) {
        if (selectedPlace != null) {
            AtlasPlace target = selectedPlace;
            AtlasPlace moved = new AtlasPlace(target.name(), target.type(), target.dimension(),
                    target.layer() + delta, target.region(), target.x(), target.z(), target.icon(),
                    target.rgb(), target.description(), target.alwaysLabel(), target.interactive());
            AtlasContent.get().removePlace(target);
            AtlasContent.get().addPlace(moved);
            selectedPlace = moved;
        } else if (selectedRoad != null) {
            AtlasRoad target = selectedRoad;
            AtlasRoad moved = target.withLayer(target.layer() + delta);
            AtlasContent.get().removeRoad(target);
            AtlasContent.get().addRoad(moved);
            selectedRoad = moved;
        } else if (selectedWaypoint != null) {
            AtlasWaypoint target = selectedWaypoint;
            AtlasWaypoint moved = new AtlasWaypoint(target.name(), target.type(), target.layer() + delta,
                    target.x(), target.z(), target.icon(), target.description());
            AtlasStorage.get(dimension).replaceWaypoint(target, moved);
            selectedWaypoint = moved;
            AtlasStorage.markDirty();
            AtlasStorage.flush();
            rebuildButtons();
            message("路标已移动到层 " + moved.layer());
            return;
        } else {
            message("只有地点/道路/路标能换层");
            return;
        }
        AtlasContent.get().saveAuthored();
        rebuildButtons();
        message("已移动到层 " + editLayer);
    }

    private AtlasButton toolButton(int x, int y, int w, String label, Tool target) {
        AtlasButton button = new AtlasButton(x, y, w, 16, label, null, () -> {
            tool = target;
            selectedPlace = null;
            selectedRegion = null;
            message("工具：" + target.label);
            rebuildButtons();
        });
        button.selected = tool == target;
        return button;
    }

    private void changeViewLayer(int delta) {
        viewLayer += delta;
        message("查看层：" + viewLayer);
        rebuildButtons();
    }

    private void renderPanelsOld2(GuiGraphics graphics) {
        if (buttons.isEmpty()) {
            return;
        }
        int panelX = this.width - MARGIN - PANEL_WIDTH;
        int panelY = MARGIN;
        int maxY = panelY + 60;
        for (AtlasButton button : buttons) {
            maxY = Math.max(maxY, button.y + button.h);
        }
        int panelH = maxY - panelY + 8;
        drawNineSlice(graphics, UI_FRAME, panelX, panelY, PANEL_WIDTH, panelH, 8);

        graphics.drawString(this.font, "地图", panelX + 10, panelY + 8, 0xFF343F3E, false);
        int rowY = MARGIN + 48;
        switch (page) {
            case PLACE -> graphics.drawString(this.font, "选素材 → 点地图放置",
                    panelX + 10, rowY, 0xFF586561, false);
            case ROAD -> graphics.drawString(this.font,
                    "路网 " + AtlasContent.get().roadsAt(dimension, viewLayer).size() + " 条",
                    panelX + 10, rowY, 0xFF586561, false);
            case REGION -> graphics.drawString(this.font,
                    "区域 " + AtlasContent.get().regionsAt(dimension, viewLayer).size(),
                    panelX + 10, rowY, 0xFF586561, false);
        }

        for (AtlasButton button : buttons) {
            renderButton(graphics, button);
        }
        renderSelectionPanel(graphics);
    }

    private void renderButton(GuiGraphics graphics, AtlasButton button) {
        if(!button.visible)return;
        boolean hovered = button.hit(lastMouseX, lastMouseY);
        AtlasUiTheme.button(graphics, button.x, button.y, button.w, button.h, button.selected || hovered);
        if (button.icon != null) {
            if(AtlasMapSymbols.marker(graphics,button.label,button.x+2,button.y+2,Math.min(button.w-4,button.h-4)))return;
            graphics.blit(button.icon, button.x + 2, button.y + 2, button.w - 4, button.h - 4,
                    0.0F, 0.0F, 32, 32, 32, 32);
        } else if (!button.label.isEmpty()) {
            String shownLabel=trimToWidth(button.label,Math.max(1,button.w-8));
            int width = this.font.width(shownLabel);
            graphics.drawString(this.font, shownLabel,
                    button.x + (button.w - width) / 2, button.y + (button.h - 8) / 2, 0xFF343F3E, false);
        }
    }

    /** 保留现有布局调用，统一改为细边冷灰面板。 */
    private void drawNineSlice(GuiGraphics graphics, ResourceLocation texture, int x, int y, int w, int h,
                               int border) {
        AtlasUiTheme.panel(graphics, x, y, w, h);
    }
    /** 选中元素的信息面板（玩家看信息，作者能改名/改层/删除）。 */
    private void renderSelectionPanel(GuiGraphics graphics) {
        if (selectedPlace == null && selectedRoad == null && selectedRegion == null && selectedWaypoint == null) {
            return;
        }
        int x = MARGIN;
        int y = this.height - MARGIN - 74;
        int w = 200;
        drawNineSlice(graphics, UI_FRAME, x, y, w, 70, 8);
        int ty = y + 8;
        if (selectedPlace != null) {
            graphics.drawString(this.font, "地点：" + selectedPlace.name(), x + 10, ty, 0xFF343F3E, false);
            graphics.drawString(this.font, "层 " + selectedPlace.layer()
                            + (selectedPlace.region().isEmpty() ? "" : " · 区域 " + selectedPlace.region()),
                    x + 10, ty + 10, 0xFF586561, false);
            graphics.drawString(this.font, "X " + (int) selectedPlace.x() + "  Z " + (int) selectedPlace.z(),
                    x + 10, ty + 20, 0xFF586561, false);
            graphics.drawString(this.font, "Delete 删除 · L 升降层", x + 10, ty + 34, 0xFF8A6A3A, false);
        } else if (selectedRoad != null) {
            graphics.drawString(this.font, "道路：" + selectedRoad.name(), x + 10, ty, 0xFF343F3E, false);
            graphics.drawString(this.font, "顶点 " + selectedRoad.points().size() + " · 层 " + selectedRoad.layer(),
                    x + 10, ty + 10, 0xFF586561, false);
            graphics.drawString(this.font, "Delete 删除 · V 编辑顶点", x + 10, ty + 24, 0xFF8A6A3A, false);
        } else if (selectedRegion != null) {
            graphics.drawString(this.font, "区域：" + selectedRegion.name(), x + 10, ty, 0xFF343F3E, false);
            graphics.drawString(this.font, "许可证 " + selectedRegion.unlockId()
                            + (AtlasUnlocks.isUnlocked(selectedRegion.id()) ? "（已解锁）" : "（未解锁）"),
                    x + 10, ty + 10, 0xFF586561, false);
            graphics.drawString(this.font, "Delete 删除 · U 切换解锁", x + 10, ty + 24, 0xFF8A6A3A, false);
        } else {
            graphics.drawString(this.font, "路标：" + selectedWaypoint.name(), x + 10, ty, 0xFF343F3E, false);
            graphics.drawString(this.font, "类型 " + selectedWaypoint.waypointType().label()
                            + " · X " + (int) selectedWaypoint.x() + "  Z " + (int) selectedWaypoint.z(),
                    x + 10, ty + 10, 0xFF586561, false);
            if (!selectedWaypoint.description().isEmpty()) {
                graphics.drawString(this.font, selectedWaypoint.description(), x + 10, ty + 20, 0xFF586561, false);
            }
            graphics.drawString(this.font, authorMode ? "Delete 删除 · T 换类型" : "点地图其他地方取消选中",
                    x + 10, ty + 34, 0xFF8A6A3A, false);
        }
    }

    private void updateHover(double mouseX, double mouseY) {
        AtlasPlace place = null;
        AtlasWaypoint waypoint = null;
        double bestPlace = 14.0D;
        for (AtlasPlace candidate : AtlasContent.get().placesAt(dimension, viewLayer)) {
            if (!elementVisible(AtlasVisibility.Kind.PLACE, candidate.region(), candidate.x(), candidate.z())) {
                continue;
            }
            double distance = Math.hypot(screenX(candidate.x()) - mouseX, screenY(candidate.z()) - mouseY);
            if (distance <= bestPlace) {
                bestPlace = distance;
                place = candidate;
            }
        }
        double bestWaypoint = 14.0D;
        for (AtlasWaypoint candidate : AtlasStorage.get(dimension).waypoints()) {
            if (candidate.layer() != viewLayer || !elementVisible(AtlasVisibility.Kind.WAYPOINT, null, candidate.x(), candidate.z())) {
                continue;
            }
            double distance = Math.hypot(screenX(candidate.x()) - mouseX, screenY(candidate.z()) - mouseY);
            if (distance <= bestWaypoint) {
                bestWaypoint = distance;
                waypoint = candidate;
            }
        }
        if (place != null && waypoint != null && bestPlace <= bestWaypoint) {
            waypoint = null;
        } else if (place != null && waypoint != null) {
            place = null;
        }
        hoveredPlace = place;
        hoveredWaypoint = waypoint;
    }

    private void trackHovered() {
        if (hoveredPlace != null) {
            AtlasTracker.set(new AtlasTracker.Target(hoveredPlace.name(), hoveredPlace.dimension(),
                    hoveredPlace.x(), hoveredPlace.z(), hoveredPlace.rgb(), hoveredPlace.isTeleport()));
            message("正在追踪：" + hoveredPlace.name());
        } else if (hoveredWaypoint != null) {
            AtlasTracker.set(new AtlasTracker.Target(hoveredWaypoint.name(), dimension,
                    hoveredWaypoint.x(), hoveredWaypoint.z(), 0x8C4A44, false));
            message("正在追踪：" + hoveredWaypoint.name());
        }
    }

    private void addPlace(double worldX, double worldZ, String type) {
        boolean teleport = AtlasPlace.TYPE_TELEPORT.equals(type);
        String region = currentRegionId(worldX, worldZ);
        String name = uniquePlaceName(teleport ? "传送点 " : "地点 ", editLayer);
        // 用调色板里选中的那个图标（历史 bug：这里硬编码了 "flag"，所以放出来全是旗帜）
        String icon = brushIcon == null || brushIcon.isEmpty() ? "pin" : brushIcon;
        AtlasPlace place = teleport
                ? AtlasPlace.teleport(name, dimension, editLayer, region, worldX, worldZ, "作者放置的传送点")
                : AtlasPlace.poi(name, dimension, editLayer, region, worldX, worldZ, icon,
                        iconColor(icon), "");
        AtlasContent.get().addPlace(place);
        AtlasContent.get().saveAuthored();
        message("已添加" + (teleport ? "传送点：" : "地点：" ) + name + "（图标 " + icon + "）"
                + (region.isEmpty() ? "" : " · 区域 " + region));
    }

    /** 生成该层未被占用的地点名（地点 key = 维度|层|名字，重名会互相覆盖：曾导致"放路标把旧路标顶掉"）。 */
    private String uniquePlaceName(String prefix, int layer) {
        java.util.List<AtlasPlace> existing = AtlasContent.get().placesAt(dimension, layer);
        int index = existing.size() + 1;
        while (index < 9999) {
            String candidate = prefix + index;
            boolean taken = false;
            for (AtlasPlace place : existing) {
                if (place.name().equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
            index++;
        }
        return prefix + Long.toString(System.nanoTime(), 36);
    }

    /** 每个预设素材给一个默认颜色（自定义图标用中性色）。 */
    private static int iconColor(String icon) {
        return switch (icon) {
            case "town" -> 0x8C4A44;
            case "village" -> 0x9A6B3F;
            case "camp" -> 0x6B7A3F;
            case "tower" -> 0x5A5A6E;
            case "ruin" -> 0x7A6A55;
            case "shop" -> 0xA8874F;
            case "quest" -> 0xD4A64A;
            case "danger" -> 0xA8322D;
            case "portal" -> 0x3E6E9E;
            case "flag" -> 0x8C4A44;
            case "tree" -> 0x5F7A4C;
            case "forest" -> 0x4F6B3A;
            case "mountain" -> 0x8B8B84;
            case "bridge" -> 0x8C6F52;
            case "gate" -> 0x6B4A2A;
            default -> 0x5A4632;
        };
    }

    /** 该坐标落在哪个区域的 id（用于把新元素自动归属到区域，实现"解锁后才可见"）。 */
    private String currentRegionId(double worldX, double worldZ) {
        AtlasRegion region = AtlasContent.get().regionAt(dimension, editLayer, worldX, worldZ);
        return region == null ? "" : region.id();
    }

    private void addRegionPoint(double worldX, double worldZ) {
        pendingRegion.add(new double[]{worldX, worldZ});
        message("区域顶点 " + pendingRegion.size() + "（回车闭合成区域）");
    }

    /** 新多边形是否"主要落在"该区域内部（中心点在内，或过半顶点在内）——用于父子归属判定。 */
    private boolean mostlyInside(AtlasRegion region, List<double[]> points) {
        if (points.isEmpty()) {
            return false;
        }
        double centerX = 0.0D;
        double centerZ = 0.0D;
        for (double[] point : points) {
            centerX += point[0];
            centerZ += point[1];
        }
        if (region.contains(centerX / points.size(), centerZ / points.size())) {
            return true;
        }
        int inside = 0;
        for (double[] point : points) {
            if (region.contains(point[0], point[1])) {
                inside++;
            }
        }
        return inside * 2 >= points.size();
    }

    /** 区域嵌套深度（顶层=0，带环保护）。 */
    private int regionDepth(AtlasRegion region) {
        int depth = 0;
        String parent = region.parent();
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (parent != null && !parent.isEmpty() && depth < 8 && visited.add(parent)) {
            depth++;
            AtlasRegion ancestor = AtlasContent.get().regionById(parent);
            parent = ancestor == null ? "" : ancestor.parent();
        }
        return depth;
    }
    private void finishRegion() {
        if (pendingRegion.size() < 3) {
            message("区域至少需要三个顶点");
            pendingRegion.clear();
            return;
        }
        // id 用"命名空间"：子区域 = 父id/子名；顶层用碰撞探测的 region_N（P0-1，绝不复用已存在 id）
        double centerX = 0.0D;
        double centerZ = 0.0D;
        for (double[] point : pendingRegion) {
            centerX += point[0];
            centerZ += point[1];
        }
        centerX /= pendingRegion.size();
        centerZ /= pendingRegion.size();
        AtlasContent content = AtlasContent.get();
        // 落在已有区域里 → 自动成为它的子区域（取**最内层**的那个；不再要求 mapArea，
        // 否则 mapArea=false 的旧大区域会让"在它里面再画一个"被判成同层重叠而画不出来）
        AtlasRegion parent = null;
        int parentDepth = -1;
        for (AtlasRegion candidate : content.regionsAt(dimension, editLayer)) {
            if (!mostlyInside(candidate, pendingRegion)) {
                continue;
            }
            int depth = regionDepth(candidate);
            boolean better = depth > parentDepth
                    || (depth == parentDepth && parent != null && candidate.mapArea() && !parent.mapArea());
            if (better) {
                parent = candidate;
                parentDepth = depth;
            }
        }
        String id;
        String name;
        if (parent != null) {
            int n = content.childrenOf(parent.id()).size() + 1;
            name = parent.name() + " · 分区 " + n;
            id = parent.id() + "/" + n;
            while (content.regionById(id) != null) {
                n++;
                id = parent.id() + "/" + n;
                name = parent.name() + " · 分区 " + n;
            }
        } else {
            id = content.nextRegionId(dimension, editLayer);
            name = "区域 " + id.substring("region_".length());
        }
        AtlasRegion region = new AtlasRegion(id, name, dimension, editLayer, new ArrayList<>(pendingRegion),
                0x5A4632, 0x7C8C5A, 40, name, centerX, centerZ, true,
                AtlasRegion.UNLOCK_LICENSE, id, false, "", new ArrayList<>(),
                parent == null ? "" : parent.id(), parent == null, true);   // 分层锁：子区域默认锁定即隐藏内容
        AtlasRegion conflict = content.overlapsSibling(region);
        if (conflict != null && parent == null && mostlyInside(conflict, pendingRegion)) {
            // 兜底：确实画在某个区域里面（例如凹形/中心点落在外面）→ 认它当父，而不是拒绝
            parent = conflict;
            int n2 = content.childrenOf(parent.id()).size() + 1;
            name = parent.name() + " · 分区 " + n2;
            id = parent.id() + "/" + n2;
            while (content.regionById(id) != null) {
                n2++;
                id = parent.id() + "/" + n2;
                name = parent.name() + " · 分区 " + n2;
            }
            region = new AtlasRegion(id, name, dimension, editLayer, new ArrayList<>(pendingRegion),
                    0x5A4632, 0x7C8C5A, 40, name, centerX, centerZ, true,
                    AtlasRegion.UNLOCK_LICENSE, id, false, "", new ArrayList<>(),
                    parent.id(), false, true);
            conflict = content.overlapsSibling(region);
        }
        if (conflict != null) {
            message("与已有区域「" + conflict.name() + "」重叠，已取消（同层区域不允许重叠；"
                    + "要嵌套请把新区域整个画在它里面）");
            pendingRegion.clear();
            return;
        }
        content.addRegion(region);
        content.saveAuthored();
        if (parent != null) {
            message("已创建子区域「" + name + "」（父：" + parent.name() + "）");
        } else {
            message("已创建大区域「" + name + "」（许可证 id 同名；它是「地图可移动范围」的单位）");
        }
        pendingRegion.clear();
    }

    /** 作者预览：右键区域切换"已解锁/未解锁"。 */
    private void toggleRegionAt(double worldX, double worldZ) {
        AtlasRegion region = AtlasContent.get().regionAt(dimension, viewLayer, worldX, worldZ);
        if (region == null) {
            message("这里没有区域");
            return;
        }
        AtlasUnlocks.toggle(region.id());
        message(region.id() + (AtlasUnlocks.isUnlocked(region.id()) ? " → 已解锁" : " → 已锁定"));
    }

    private void addWaypoint(double worldX, double worldZ) {
        AtlasMapData data = AtlasStorage.get(dimension);
        String name = "路标 " + data.nextWaypointIndex();
        data.addWaypoint(name, worldX, worldZ);
        AtlasStorage.markDirty();
        message("已添加路标：" + name);
    }

    private void addRoadPoint(double worldX, double worldZ) {
        pendingRoad.add(new double[]{worldX, worldZ});
        message("道路点数 " + pendingRoad.size() + "（回车结束）");
    }

    private void finishRoad() {
        if (pendingRoad.size() >= 2) {
            String name = "道路 " + (AtlasContent.get().roadsAt(dimension, editLayer).size() + 1);
            String id = AtlasContent.get().nextRoadId(dimension, editLayer);   // 统一 id 生成（永不复用）
            String region = currentRegionId(pendingRoad.get(0)[0], pendingRoad.get(0)[1]);
            List<AtlasRoadNode> nodes = new ArrayList<>();
            // 手绘时两端自动打端点节点，方便后面从端点开岔
            nodes.add(AtlasRoadNode.of(0, "起点").withKind(AtlasRoadNode.KIND_END));
            if (pendingRoad.size() > 1) {
                nodes.add(AtlasRoadNode.of(pendingRoad.size() - 1, "终点")
                        .withKind(AtlasRoadNode.KIND_END));
            }
            AtlasContent.get().addRoad(new AtlasRoad(id, name, dimension, editLayer, region,
                    new ArrayList<>(pendingRoad), 3, 0x8C6F52, false, nodes, ""));
            AtlasContent.get().saveAuthored();
            message("已保存道路：" + name + "（两端已生成节点）");
        } else {
            message("道路至少需要两个点");
        }
        pendingRoad.clear();
    }

    /** 在选中的道路上插入节点：把折线在最近点处切开并登记节点。 */
    private void insertNodeOnSelectedRoad(double worldX, double worldZ) {
        if (selectedRoad == null) {
            message("先选中一条道路");
            return;
        }
        AtlasRoad road = currentRoad(selectedRoad);
        double[] projected = projectOnPolyline(road.points(), worldX, worldZ);
        if (projected == null) {
            message("道路太短，无法加点");
            return;
        }
        int segment = (int) projected[0];
        double t = projected[1];
        if (t < 0.02D || t > 0.98D) {
            // 落在已有顶点上：直接把那个顶点升级为节点
            int index = t < 0.5D ? segment : segment + 1;
            List<AtlasRoadNode> nodes = new ArrayList<>(road.nodes());
            if (road.nodeAtIndex(index) == null && index < road.points().size()) {
                nodes.add(AtlasRoadNode.of(index, "节点 " + (nodes.size() + 1)));
                AtlasContent.get().replaceRoad(road, road.withNodes(nodes));
                AtlasContent.get().saveAuthored();
                message("已在已有顶点上添加节点（第 " + index + " 个点）");
            } else {
                message("这里已经有节点了");
            }
            return;
        }
        List<double[]> points = new ArrayList<>(road.points());
        points.add(segment + 1, new double[]{projected[2], projected[3]});
        List<AtlasRoadNode> nodes = new ArrayList<>();
        for (AtlasRoadNode node : road.nodes()) {
            nodes.add(node.index() > segment ? node.withIndex(node.index() + 1) : node);
        }
        nodes.add(AtlasRoadNode.of(segment + 1, "节点 " + (nodes.size() + 1)));
        AtlasContent.get().replaceRoad(road, road.withPoints(points, nodes));
        AtlasContent.get().saveAuthored();
        AtlasRoad refreshed = AtlasContent.get().roadByKey(road.key());
        selectedRoad = refreshed != null ? refreshed : road;
        tool = Tool.SELECT;
        rebuildButtons();
        message("已在道路上加入节点（第 " + (segment + 1) + " 个点）");
    }

    /** 点到折线的最近投影：返回 {段号, t, 投影x, 投影z}。 */
    private double[] projectOnPolyline(List<double[]> points, double x, double z) {
        if (points.size() < 2) {
            return null;
        }
        double bestDistance = Double.MAX_VALUE;
        double[] best = null;
        for (int i = 0; i + 1 < points.size(); i++) {
            double x1 = points.get(i)[0];
            double z1 = points.get(i)[1];
            double x2 = points.get(i + 1)[0];
            double z2 = points.get(i + 1)[1];
            double dx = x2 - x1;
            double dz = z2 - z1;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, ((x - x1) * dx + (z - z1) * dz) / lengthSq));
            double px = x1 + t * dx;
            double pz = z1 + t * dz;
            double distance = Math.hypot(x - px, z - pz);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new double[]{i, t, px, pz};
            }
        }
        return best;
    }

    private void deleteHovered() {
        if (hoveredPlace != null) {
            AtlasContent.get().removePlace(hoveredPlace);
            AtlasContent.get().saveAuthored();
            message("已删除：" + hoveredPlace.name());
            hoveredPlace = null;
        } else if (hoveredWaypoint != null) {
            AtlasStorage.get(dimension).removeWaypoint(hoveredWaypoint);
            AtlasStorage.markDirty();
            message("已删除路标：" + hoveredWaypoint.name());
            hoveredWaypoint = null;
        }
    }

    /** 界面内反馈（不用退出去看聊天栏）。 */
    private String toast = "";
    private long toastUntil;

    private void message(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        toast = text;
        toastUntil = System.currentTimeMillis() + 5000L;
        LOGGER.info("[BiyiAtlas] {}", text);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("[避役图志] " + text), false);
        }
    }

    /** 提示条避开顶部导航和路线指引。 */
    private void renderToast(GuiGraphics graphics) {
        if (toast.isEmpty() || System.currentTimeMillis() > toastUntil) {
            return;
        }
        int maxWidth = drawerOpen ? uiFrame().drawer().x()-MARGIN-8 : this.width-MARGIN*2;
        if(maxWidth<60)return;
        int boxWidth = Math.min(maxWidth, this.font.width(toast) + 22);
        int x = MARGIN;
        int y = this.height - (AtlasRoute.has()?72:46);
        drawNineSlice(graphics, UI_FRAME, x, y, boxWidth, 22, 8);
        graphics.drawString(this.font, trimToWidth(toast, boxWidth - 14), x + 7, y + 7, 0xFF343F3E, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(revealing()){
            if(keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE||keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE||keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER){skipReveal();return true;}
            if(AtlasKeys.OPEN.matches(keyCode,scanCode)){onClose();return true;}
            return true;
        }
        if(authorMode&&hasControlDown()&&(keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_Z||keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_Y)){
            moveEditHistory(keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_Y||hasShiftDown());return true;
        }
        if (!authorMode && AtlasKeys.OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_C) {
            centerOnPlayer();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE && authorMode && hasSelection()) {
            deleteSelection();
            return true;
        }
        // E = 编辑当前选中元素（作者）
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_E && authorMode && hasSelection()) {
            openEditForSelection();
            return true;
        }
        // 区域解锁快捷键：U = 解锁/锁定；G = 生成许可证（作用于选中区域，否则作用于当前大区域）
        if (authorMode && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_U
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_G)) {
            AtlasRegion target = selectedRegion != null ? selectedRegion : viewArea;
            if (target == null) {
                message("先用 U/G 前：在区域树里点一个区域，或把视图切到某个已解锁大区域");
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_U) {
                AtlasUnlocks.toggle(target.id());
                message(target.name() + (AtlasUnlocks.isUnlocked(target.id()) ? " → 已解锁" : " → 已锁定"));
            } else {
                requestLicense(target.id());
            }
            rebuildButtons();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_T && authorMode && selectedWaypoint != null) {
            WaypointType next = selectedWaypoint.waypointType().next();
            AtlasWaypoint updated = new AtlasWaypoint(selectedWaypoint.name(), next.name(),
                    selectedWaypoint.layer(), selectedWaypoint.x(), selectedWaypoint.z(), next.icon(),
                    selectedWaypoint.description());
            AtlasStorage.get(dimension).replaceWaypoint(selectedWaypoint, updated);
            selectedWaypoint = updated;
            AtlasStorage.markDirty();
            AtlasStorage.flush();
            message("路标类型改为：" + next.label());
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_L && authorMode && hasSelection()) {
            int delta = hasShiftDown() ? -1 : 1;
            if (selectedPlace != null) {
                AtlasPlace moved = new AtlasPlace(selectedPlace.name(), selectedPlace.type(),
                        selectedPlace.dimension(), selectedPlace.layer() + delta, selectedPlace.region(),
                        selectedPlace.x(), selectedPlace.z(), selectedPlace.icon(), selectedPlace.rgb(),
                        selectedPlace.description(), selectedPlace.alwaysLabel(), selectedPlace.interactive());
                AtlasContent.get().removePlace(selectedPlace);
                AtlasContent.get().addPlace(moved);
                selectedPlace = moved;
            } else if (selectedRoad != null) {
                AtlasRoad moved = selectedRoad.withLayer(selectedRoad.layer() + delta);
                AtlasContent.get().removeRoad(selectedRoad);
                AtlasContent.get().addRoad(moved);
                selectedRoad = moved;
            }
            AtlasContent.get().saveAuthored();
            message("已把选中元素移动到层 " + editLayer);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET) {
            cycleDimension(-1);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET) {
            cycleDimension(1);
            return true;
        }
        // 层切换：PageUp/PageDown 切换查看层；作者模式下 Shift+PageUp/PageDown 切换编辑层
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
            if (hasShiftDown() && authorMode) {
                editLayer++;
                viewLayer = editLayer;
                AtlasRoadRecorder.setLayer(editLayer);
                message("编辑层：" + editLayer);
            } else {
                viewLayer++;
                message("查看层：" + viewLayer);
            }
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) {
            if (hasShiftDown() && authorMode) {
                editLayer--;
                viewLayer = editLayer;
                AtlasRoadRecorder.setLayer(editLayer);
                message("编辑层：" + editLayer);
            } else {
                viewLayer--;
                message("查看层：" + viewLayer);
            }
            return true;
        }
        if (authorMode) {
            if (keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && keyCode <= org.lwjgl.glfw.GLFW.GLFW_KEY_7) {
                tool = Tool.values()[keyCode - org.lwjgl.glfw.GLFW.GLFW_KEY_1];
                message("当前工具：" + tool.label);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                if (tool == Tool.REGION) {
                    finishRegion();
                } else {
                    finishRoad();
                }
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_R) {
                AtlasRoadRecorder.toggle();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_E) {
                ClientEvents.uploadContent();
                minecraft.player.connection.sendCommand("biyi_atlas project export shared");
                message("正在导出完整工程 shared.biyi.zip");
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                    && (!pendingRoad.isEmpty() || !pendingRegion.isEmpty())) {
                pendingRoad.clear();
                pendingRegion.clear();
                message("已取消当前绘制");
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        revealRegion=null;AtlasUnlockReveal.clear();
        super.onClose();
        AtlasStorage.flush();
        AtlasContent.get().saveAuthored();
    }

    /** 作者模式下的编辑工具。 */
    private enum Tool {
        SELECT("选择"),
        PLACE("地点"),
        TELEPORT("传送点"),
        ROAD("道路"),
        WAYPOINT("路标"),
        REGION("区域"),
        RECT("框选"),
        NODE("加点"),
        UNLOCK("解锁开关");

        final String label;

        Tool(String label) {
            this.label = label;
        }
    }

    // ============================================================ 面板渲染（重建）

    private void renderPanels(GuiGraphics g) {
        if(drawerOpen){
            var frame=uiFrame();var dock=frame.drawer();
            AtlasUiTheme.panel(g,dock.x(),dock.y(),dock.width(),dock.height());
            g.drawString(font,sectionName(drawerSection),dock.x()+10,dock.y()+10,0xFF343F3E,false);
            String detail=switch(drawerSection){
                case INSPECTOR->hasSelection()?selectedTitle():"在地图上选择一个对象";
                case REGIONS->authorMode?(selectedRegion==null?"先选择区域，再准备和发布":"当前："+selectedRegion.name()):"选择已开放的地图范围";
                case TOOLS->"先选操作，再向下选择地点素材";
                case WAYPOINT->"点击定位，星号可置顶";
                case VIEW->"层 "+viewLayer+" · "+dimension.getPath();
                case SETTINGS->"地图显示与探索规则";
                case AI->AtlasAiBridge.connectionStatus();
                default->"";
            };
            g.drawString(font,trimToWidth(detail,dock.width()-20),dock.x()+10,dock.y()+26,0xFF65716C,false);
            if(drawerSection==UiSection.INSPECTOR&&hasSelection()){
                g.drawString(font,trimToWidth(selectionDescription(),dock.width()-20),dock.x()+10,dock.y()+36,0xFF65716C,false);
            }
            if(drawerMaxScroll>0){
                int track=frame.contentBottom()-frame.contentTop();
                int thumb=Math.max(12,track*track/(track+drawerMaxScroll));
                int y=frame.contentTop()+(track-thumb)*drawerScroll/Math.max(1,drawerMaxScroll);
                AtlasUiTheme.rect(g,dock.x()+dock.width()-5,y,2,thumb,0xFF8C9A94);
            }
        }
        if(drawerOpen&&drawerSection==UiSection.AI){
            var dock=uiFrame().drawer();int y=uiFrame().contentTop();
            for(String line:AtlasAiBridge.infoLines()){
                g.drawString(font,trimToWidth(line,dock.width()-20),dock.x()+10,y,0xFF65716C,false);y+=14;
            }
        }
        renderRouteHud(g);renderToast(g);
        for(var button:buttons)if(button.visible)renderButton(g,button);
        for(var button:buttons)if(button.visible&&button.hit(lastMouseX,lastMouseY)
                &&(button.icon!=null||font.width(button.label)>button.w-8)){
            g.renderTooltip(font,Component.literal(button.label),(int)lastMouseX,(int)lastMouseY);break;
        }
    }
    private String selectedTitle(){
        if(selectedPlace!=null)return selectedPlace.name();
        if(selectedRegion!=null)return selectedRegion.name();
        if(selectedWaypoint!=null)return selectedWaypoint.name();
        if(selectedImage!=null)return selectedImage.name();
        if(selectedNode!=null)return selectedNode.name();
        return selectedRoad!=null?selectedRoad.name():"未选对象";
    }
    private String selectionDescription(){
        if(selectedPlace!=null)return selectedPlace.description().isEmpty()?"地点":selectedPlace.description();
        if(selectedWaypoint!=null)return selectedWaypoint.description().isEmpty()?"路标":selectedWaypoint.description();
        if(selectedRegion!=null)return "区域 · "+(AtlasUnlocks.isVisible(selectedRegion.id())?"已开放":"未开放")+(authorMode?" · "+selectedRegion.id():"");
        if(selectedRoad!=null)return "道路 · "+selectedRoad.points().size()+" 个顶点";
        return "选择下方操作";
    }

    /** 点选元素后弹出的信息窗：名称、类型、坐标、描述 + 操作按钮。 */
    private void renderSelectionWindow(GuiGraphics graphics) {
        if (!hasSelection()) {
            return;
        }
        int x = MARGIN;
        int y = selectionWindowY();
        drawNineSlice(graphics, UI_FRAME, x, y, SELECTION_WINDOW_W, SELECTION_WINDOW_H, 8);
        int tx = x + 10;
        String title;
        String line1;
        String line2;
        if (selectedWaypoint != null) {
            title = "路标 · " + selectedWaypoint.name();
            line1 = "类型 " + selectedWaypoint.waypointType().label()
                    + " · X " + (int) selectedWaypoint.x() + "  Z " + (int) selectedWaypoint.z();
            line2 = selectedWaypoint.description().isEmpty() ? "（无描述）" : selectedWaypoint.description();
        } else if (selectedPlace != null) {
            title = "地点 · " + selectedPlace.name();
            line1 = "图标 " + selectedPlace.icon() + " · 层 " + selectedPlace.layer()
                    + (selectedPlace.region().isEmpty() ? "" : " · 区域 " + selectedPlace.region());
            line2 = selectedPlace.description().isEmpty() ? "（无描述）" : selectedPlace.description();
        } else if (selectedNode != null) {
            AtlasRoad nodeRoad = selectedRoad == null ? null : currentRoad(selectedRoad);
            double[] point = nodePoint(nodeRoad, selectedNode);
            title = "节点 · " + selectedNode.name() + "（" + kindLabel(selectedNode.kind()) + "）";
            line1 = (nodeRoad == null ? "线路已失效" : "线路 " + nodeRoad.name())
                    + " · 第 " + selectedNode.index() + " 个点";
            line2 = point == null ? "坐标无效"
                    : "X " + (int) point[0] + "  Z " + (int) point[1];
        } else if (selectedRoad != null) {
            title = "道路 · " + selectedRoad.name();
            line1 = "顶点 " + selectedRoad.points().size() + " · 节点 " + selectedRoad.nodes().size()
                    + " · 层 " + selectedRoad.layer();
            line2 = selectedRoad.branchOf().isEmpty() ? "（主线）" : "支线 ← " + selectedRoad.branchOf();
        } else if (selectedImage != null) {
            title = "地点图 · " + selectedImage.name();
            line1 = "尺寸 " + (int) (selectedImage.width() * selectedImage.blocksPerPixel()) + "×"
                    + (int) (selectedImage.height() * selectedImage.blocksPerPixel()) + " 方块 · 层 "
                    + selectedImage.layer();
            line2 = selectedImage.description().isEmpty() ? "（无描述）" : selectedImage.description();
        } else if (selectedRegion != null) {
            title = "区域 · " + selectedRegion.name();
            line1 = "许可证 " + selectedRegion.unlockId()
                    + (AtlasUnlocks.isUnlocked(selectedRegion.id()) ? " · 已解锁" : " · 未解锁");
            AtlasRegion current = currentRegion(selectedRegion.id());
            line2 = "边界 " + selectedRegion.outline().size() + " 顶点 · 层 " + selectedRegion.layer();
        } else {
            title = "未选中元素";
            line1 = "在地图上点一下元素";
            line2 = "";
        }
        graphics.drawString(this.font, trimToWidth(title, SELECTION_WINDOW_W - 20), tx, y + 8, 0xFF343F3E, false);
        graphics.drawString(this.font, trimToWidth(line1, SELECTION_WINDOW_W - 20), tx, y + 20, 0xFF586561, false);
        graphics.drawString(this.font, trimToWidth(line2, SELECTION_WINDOW_W - 20), tx, y + 32, 0xFF586561, false);
    }

    private int selectionWindowY() {
        int bottom = authorMode ? this.height - MARGIN - bottomPanelHeight() : this.height - MARGIN;
        return bottom - SELECTION_WINDOW_H - 4;
    }

    // ============================================================ 选中项操作按钮（重建）

    private void addSelectionButtons() {
        buildingSection=UiSection.INSPECTOR;
        if (!hasSelection()) {
            return;
        }
        int x = MARGIN + 8;
        int y = selectionWindowY() + SELECTION_WINDOW_H - 42;
        int y2 = y + 20;
        if (selectedWaypoint != null) {
            final AtlasWaypoint target = selectedWaypoint;
            buttons.add(new AtlasButton(x, y, 62, 16, "定位", null,
                    () -> setCenter(target.x(), target.z())));
            buttons.add(new AtlasButton(x + 66, y, 62, 16, "追踪", null, () -> {
                AtlasTracker.set(new AtlasTracker.Target(target.name(), dimension, target.x(), target.z(),
                        target.waypointType().rgb(), target.waypointType() == WaypointType.TELEPORT));
                message("开始追踪：" + target.name());
            }));
            if (authorMode) {
                buttons.add(new AtlasButton(x + 132, y, 62, 16, "传送", null, () -> teleportTo(target)));
                buttons.add(new AtlasButton(x, y2, 96, 16, "编辑信息", null, this::openEditForSelection));
                buttons.add(new AtlasButton(x + 100, y2, 60, 16, "删除", null, this::deleteSelection));
                addRouteButton(x + 164, y2, 88, target.x(), target.z(), target.name());
            }
        } else if (selectedPlace != null) {
            final AtlasPlace target = selectedPlace;
            buttons.add(new AtlasButton(x, y, 62, 16, "追踪", null, () -> {
                AtlasTracker.set(new AtlasTracker.Target(target.name(), target.dimension(), target.x(),
                        target.z(), target.rgb(), false));
                message("开始追踪：" + target.name());
            }));
            if (authorMode) {
                buttons.add(new AtlasButton(x + 66, y, 96, 16, "编辑信息", null, this::openEditForSelection));
                buttons.add(new AtlasButton(x + 166, y, 60, 16, "层 +1", null, () -> moveSelectionLayer(1)));
                buttons.add(new AtlasButton(x + 230, y, 60, 16, "层 -1", null, () -> moveSelectionLayer(-1)));
                buttons.add(new AtlasButton(x + 294, y, 60, 16, "删除", null, this::deleteSelection));
                addRouteButton(x + 164, y2, 88, target.x(), target.z(), target.name());
            }
        } else if (selectedImage != null) {
            final AtlasMapImage target = selectedImage;
            buttons.add(new AtlasButton(x, y, 62, 16, "追踪", null, () -> {
                AtlasTracker.set(new AtlasTracker.Target(target.name(), target.dimension(),
                        target.centerX(), target.centerZ(), 0x8C6A3F, false));
                message("开始追踪：" + target.name());
            }));
            buttons.add(new AtlasButton(x + 66, y, 62, 16, "定位", null,
                    () -> setCenter(target.centerX(), target.centerZ())));
            if (authorMode) {
                buttons.add(new AtlasButton(x + 132, y, 96, 16, "编辑信息", null, this::openEditForSelection));
                buttons.add(new AtlasButton(x + 232, y, 60, 16, "删除", null, this::deleteSelection));
            }
        } else if (selectedNode != null) {
            AtlasRoad nodeRoad = currentRoad(selectedRoad == null ? null : selectedRoad);
            AtlasRoadNode node = selectedNode;
            if (nodeRoad == null) {
                buttons.add(new AtlasButton(x, y, 120, 16, "清除失效选中", null, () -> {
                    clearSelection();
                    rebuildButtons();
                }));
            } else {
                final AtlasRoad road = nodeRoad;
                buttons.add(new AtlasButton(x, y, 62, 16, "定位", null, () -> locateNode(road, node)));
                if (authorMode) {
                    buttons.add(new AtlasButton(x + 66, y, 78, 16, "传送到节点", null,
                            () -> teleportToNode(road, node)));
                    buttons.add(new AtlasButton(x + 148, y, 78, 16, "从此开岔", null,
                            () -> startBranch(road, node)));
                    buttons.add(new AtlasButton(x + 230, y, 62, 16, "改名", null,
                            () -> renameNode(road, node)));
                    buttons.add(new AtlasButton(x, y2, 78, 16, "删除节点", null, () -> removeNode(road, node)));
                    buttons.add(new AtlasButton(x + 82, y2, 100, 16, "编辑线路信息", null,
                            this::openEditForSelection));
                    double[] nodePos = nodePoint(road, node);
                    if (nodePos != null) {
                        addRouteButton(x + 186, y2, 88, nodePos[0], nodePos[1], node.name());
                    }
                } else {
                    buttons.add(new AtlasButton(x + 66, y, 78, 16, "追踪", null,
                            () -> trackNode(road, node)));
                }
            }
        } else if (selectedRoad != null) {
            if (authorMode) {
                buttons.add(new AtlasButton(x, y, 96, 16, "编辑信息", null, this::openEditForSelection));
                buttons.add(new AtlasButton(x + 100, y, 62, 16, "加点", null, () -> {
                    tool = Tool.NODE;
                    message("加节点：在选中道路上点一下（自动插到最近的折线位置）");
                }));
                buttons.add(new AtlasButton(x + 166, y, 78, 16,
                        "点 " + currentRoad(selectedRoad).points().size() + "/节 "
                                + currentRoad(selectedRoad).nodes().size(), null, () -> {
                    AtlasRoad road = currentRoad(selectedRoad);
                    if (road.nodes().isEmpty()) {
                        message("这条线还没有节点：点「加点」在道路上加一个");
                    } else {
                        List<String> names = new ArrayList<>();
                        for (AtlasRoadNode node : road.nodes()) {
                            names.add(node.name() + "(" + kindLabel(node.kind()) + ")");
                        }
                        message("节点：" + String.join("、", names));
                    }
                }));
                buttons.add(new AtlasButton(x + 248, y, 62, 16, "删除", null, this::deleteSelection));
                buttons.add(new AtlasButton(x, y2, 130, 16, "按区域切割成多条", null,
                        () -> splitRoadByRegions(currentRoad(selectedRoad))));
                buttons.add(new AtlasButton(x + 134, y2, 78, 16, "标记交界点", null,
                        () -> autoSplitRoadAtRegions(currentRoad(selectedRoad))));
                buttons.add(new AtlasButton(x + 216, y2, 94, 16, "记录支线", null, () -> {
                    AtlasRoad road = currentRoad(selectedRoad);
                    AtlasRoadNode start = selectedNode;
                    if (start == null) {
                        start = nearestNodeToCenter(road);
                    }
                    if (start == null) {
                        message("这条线还没有节点：先「加点」并在节点上点一下，再用「从此开岔」");
                        return;
                    }
                    startBranch(road, start);
                }));
            }
        } else if (selectedRegion != null) {
            final AtlasRegion target = selectedRegion;
            // 玩家侧：只看信息与图鉴；所有编辑按钮都在 authorMode 里
            buttons.add(new AtlasButton(x, y, 92, 16, "进入该区域", null,
                    () -> switchViewArea(currentRegion(target.id()))));
            addRouteButton(x + 98, y, 64, target.labelX(), target.labelZ(), target.name());
            if (authorMode) {
            // ↓ 以下按钮都属于上面已打开的 authorMode 作用域
            buttons.add(new AtlasButton(x, y2, 60, 16,
                        AtlasUnlocks.isUnlocked(target.id()) ? "锁定区域" : "解锁区域", null, () -> {
                    AtlasUnlocks.toggle(target.id());
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(x + 66, y2, 60, 16, "生成许可证", null,
                        () -> requestLicense(target.id())));
                buttons.add(new AtlasButton(x + 132, y2, 50, 16, "编辑信息", null,
                        this::openEditForSelection));
                // 顶点拖拽编辑：按住顶点拖动改形状；点到边中点=插顶点；Shift+点顶点=删顶点
                buttons.add(new AtlasButton(x + 188, y2, 50, 16,
                        vertexEditRegion != null && vertexEditRegion.id().equals(target.id()) ? "顶点✔" : "编辑顶点",
                        null, () -> {
                    AtlasRegion current = currentRegion(target.id());
                    vertexEditRegion = vertexEditRegion != null && vertexEditRegion.id().equals(current.id())
                            ? null : current;
                    vertexDragIndex = -1;
                    message(vertexEditRegion == null ? "已退出顶点编辑"
                            : "顶点编辑：拖顶点改形状 · 点边中点插入 · Shift+点顶点删除");
                    rebuildButtons();
                }));
                buttons.add(new AtlasButton(x + 300, y, 44, 16, "删除", null, () -> {
                    AtlasRegion current = currentRegion(target.id());
                    int childCount = AtlasContent.get().descendantsOf(target.id()).size();
                    if (childCount > 0 && !target.id().equals(confirmRegionDelete)) {
                        confirmRegionDelete = target.id();
                        message("「" + current.name() + "」有 " + childCount + " 个子区域会一起删除，再点一次确认");
                        return;
                    }
                    confirmRegionDelete = null;
                    deleteSelection();
                    rebuildButtons();
                }));
            }
        }
    }

    // ============================================================ 道路节点与断点（重建）

    private String kindLabel(String kind) {
        return switch (kind) {
            case AtlasRoadNode.KIND_JUNCTION -> "岔口";
            case AtlasRoadNode.KIND_END -> "端点";
            case AtlasRoadNode.KIND_BOUNDARY -> "区域交界";
            default -> "锚点";
        };
    }

    /** 离当前视图中心最近的节点（"记录支线"未选节点时的兜底，避免点了没反应）。 */
    private AtlasRoadNode nearestNodeToCenter(AtlasRoad road) {
        if (road == null) {
            return null;
        }
        AtlasRoadNode best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AtlasRoadNode node : road.nodes()) {
            double[] point = nodePoint(road, node);
            if (point == null) {
                continue;
            }
            double distance = Math.hypot(point[0] - centerX, point[1] - centerZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
        }
        return best;
    }

    private double[] nodePoint(AtlasRoad road, AtlasRoadNode node) {
        if (road == null || node == null || node.index() < 0 || node.index() >= road.points().size()) {
            return null;
        }
        return road.points().get(node.index());
    }

    private void locateNode(AtlasRoad road, AtlasRoadNode node) {
        double[] point = nodePoint(road, node);
        if (point != null) {
            setCenter(point[0], point[1]);
            message("已定位到节点：" + node.name());
        }
    }

    private void trackNode(AtlasRoad road, AtlasRoadNode node) {
        double[] point = nodePoint(road, node);
        if (point != null) {
            AtlasTracker.set(new AtlasTracker.Target(node.name(), road.dimension(), point[0], point[1],
                    0x8C6A3F, false));
            message("开始追踪节点：" + node.name());
        }
    }

    private void teleportToNode(AtlasRoad road, AtlasRoadNode node) {
        double[] point = nodePoint(road, node);
        if (point == null) {
            return;
        }
        AtlasNetwork.sendTeleportRequest(road.dimension(), point[0], point[1]);
        message("正在传送到节点：" + node.name() + "（服务端校验坐标与冷却）");
    }

    private void startBranch(AtlasRoad road, AtlasRoadNode node) {
        AtlasRoadRecorder.beginBranch(road, node);
        message("已从「" + node.name() + "」开岔：关掉地图后走路，再按一次 R 结束支线");
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private void renameNode(AtlasRoad road, AtlasRoadNode node) {
        List<AtlasRoadNode> nodes = new ArrayList<>(road.nodes());
        int index = nodes.indexOf(node);
        if (index < 0) {
            return;
        }
        String newName = "节点 " + (node.index() + 1);
        nodes.set(index, node.withName(newName));
        AtlasContent.get().replaceRoad(road, road.withNodes(nodes));
        AtlasContent.get().saveAuthored();
        selectedNode = nodes.get(index);
        rebuildButtons();
        message("节点已重命名为：" + newName);
    }

    private void removeNode(AtlasRoad road, AtlasRoadNode node) {
        List<AtlasRoadNode> nodes = new ArrayList<>(road.nodes());
        nodes.remove(node);
        AtlasContent.get().replaceRoad(road, road.withNodes(nodes));
        int brokenBranches = AtlasContent.get().clearBranchReferences(road.key(), node.id());
        AtlasContent.get().saveAuthored();
        selectedNode = null;
        rebuildButtons();
        message("已删除节点（折线顶点保留）"
                + (brokenBranches > 0 ? "，同时清理了 " + brokenBranches + " 条支线引用" : ""));
    }

    private static double cross2(double ax, double az, double bx, double bz) {
        return ax * bz - az * bx;
    }

    /**
     * 按区域边界把一条路**切成多条**：每个区域内的部分成为独立的一条路（原路保留第一段）。
     *
     * <p>和"交界断点"不同：这里产出的是多条线路，路网列表会立即更新。</p>
     */
    private void splitRoadByRegions(AtlasRoad road) {
        List<double[]> points = road.points();
        if (points.size() < 2) {
            message("道路太短，无法切割");
            return;
        }
        List<AtlasRegion> regions = AtlasContent.get().regionsAt(dimension, road.layer());
        if (regions.isEmpty()) {
            message("这一层还没有区域，先画区域再切割");
            return;
        }
        // 1) 所有边界交点（按"沿折线的弧长"排序）
        List<double[]> cuts = new ArrayList<>();
        double walked = 0.0D;
        for (int i = 0; i + 1 < points.size(); i++) {
            double[] a = points.get(i);
            double[] b = points.get(i + 1);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (length < 1.0E-6D) {
                continue;
            }
            List<Double> ts = new ArrayList<>();
            for (AtlasRegion region : regions) {
                List<double[]> polygon = region.outline();
                for (int j = 0; j < polygon.size(); j++) {
                    double[] c = polygon.get(j);
                    double[] d = polygon.get((j + 1) % polygon.size());
                    double den = cross2(b[0] - a[0], b[1] - a[1], d[0] - c[0], d[1] - c[1]);
                    if (Math.abs(den) < 1.0E-9D) {
                        continue;
                    }
                    double t = cross2(c[0] - a[0], c[1] - a[1], d[0] - c[0], d[1] - c[1]) / den;
                    double u = cross2(c[0] - a[0], c[1] - a[1], b[0] - a[0], b[1] - a[1]) / den;
                    if (t > 0.001D && t < 0.999D && u >= -0.001D && u <= 1.001D) {
                        ts.add(t);
                    }
                }
            }
            Collections.sort(ts);
            for (double t : ts) {
                cuts.add(new double[]{walked + t * length, a[0] + (b[0] - a[0]) * t,
                        a[1] + (b[1] - a[1]) * t});
            }
            walked += length;
        }
        if (cuts.isEmpty()) {
            message("这条路没有穿过区域边界，不需要切割");
            return;
        }
        cuts.sort(java.util.Comparator.comparingDouble(cut -> cut[0]));
        // 2) 按交点把折线切成片段
        List<List<double[]>> pieces = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        current.add(points.get(0));
        walked = 0.0D;
        int cutIndex = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            double[] a = points.get(i);
            double[] b = points.get(i + 1);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double segStart = walked;
            double segEnd = walked + length;
            while (cutIndex < cuts.size() && length > 1.0E-6D) {
                double[] cut = cuts.get(cutIndex);
                if (cut[0] <= segStart + 1.0E-6D) {
                    cutIndex++;
                    continue;
                }
                if (cut[0] >= segEnd - 1.0E-6D) {
                    break;
                }
                current.add(new double[]{cut[1], cut[2]});
                pieces.add(current);
                current = new ArrayList<>();
                current.add(new double[]{cut[1], cut[2]});
                cutIndex++;
            }
            current.add(b);
            walked = segEnd;
        }
        pieces.add(current);
        pieces.removeIf(piece -> piece.size() < 2);
        if (pieces.size() < 2) {
            message("没能切出多段（可能只是碰到区域角落）");
            return;
        }
        // 3) 第一段沿用原路（保持 id/名字），其余各建一条新路
        int added = 0;
        for (int i = 0; i < pieces.size(); i++) {
            List<double[]> piece = pieces.get(i);
            double[] middle = piece.get(piece.size() / 2);
            AtlasRegion inside = AtlasContent.get().regionInnermostAt(dimension, road.layer(),
                    middle[0], middle[1]);
            String regionId = inside == null ? "" : inside.id();
            List<AtlasRoadNode> nodes = new ArrayList<>();
            nodes.add(AtlasRoadNode.of(0, "起点").withKind(AtlasRoadNode.KIND_END));
            if (piece.size() > 1) {
                nodes.add(AtlasRoadNode.of(piece.size() - 1, "终点").withKind(AtlasRoadNode.KIND_END));
            }
            if (i == 0) {
                AtlasContent.get().replaceRoad(road, road.withPoints(piece, nodes).withRegion(regionId));
            } else {
                String id = AtlasContent.get().nextRoadId(dimension, road.layer());
                String name = road.name() + " · 段" + (i + 1);
                AtlasContent.get().addRoad(new AtlasRoad(id, name, road.dimension(), road.layer(),
                        regionId, new ArrayList<>(piece), road.width(), road.rgb(), road.dashed(),
                        nodes, ""));
                added++;
            }
        }
        AtlasContent.get().saveAuthored();
        AtlasRoad refreshed = AtlasContent.get().roadByKey(road.key());
        selectedRoad = refreshed != null ? refreshed : road;
        selectedNode = null;
        rebuildButtons();
        message("已按区域切成 " + pieces.size() + " 条路（新增 " + added + " 条，原路保留第一段）"
                + " · 路网现在共 " + AtlasContent.get().roadsAt(dimension, viewLayer).size() + " 条");
    }

    /**
     * 在道路与区域边界的交点上生成节点（kind = 区域交界）。
     * 逐段与区域多边形各边求交 → 按弧长插入顶点 → 旧节点按坐标重映射；重复执行先清掉旧交界节点。
     */
    private void autoSplitRoadAtRegions(AtlasRoad road) {
        List<double[]> oldPoints = road.points();
        if (oldPoints.size() < 2) {
            message("道路太短，无法断点");
            return;
        }
        List<AtlasRegion> regions = AtlasContent.get().regionsAt(dimension, road.layer());
        if (regions.isEmpty()) {
            message("这一层还没有区域，先画区域再断点");
            return;
        }
        List<double[]> newPoints = new ArrayList<>();
        List<Integer> boundaryIndices = new ArrayList<>();
        int inserted = 0;
        int droppedNodes = 0;
        for (int i = 0; i + 1 < oldPoints.size(); i++) {
            double[] a = oldPoints.get(i);
            double[] b = oldPoints.get(i + 1);
            newPoints.add(a);
            List<Double> ts = new ArrayList<>();
            for (AtlasRegion region : regions) {
                List<double[]> polygon = region.outline();
                for (int j = 0; j < polygon.size(); j++) {
                    double[] c = polygon.get(j);
                    double[] d = polygon.get((j + 1) % polygon.size());
                    double den = cross2(b[0] - a[0], b[1] - a[1], d[0] - c[0], d[1] - c[1]);
                    if (Math.abs(den) < 1.0E-9D) {
                        continue;
                    }
                    double t = cross2(c[0] - a[0], c[1] - a[1], d[0] - c[0], d[1] - c[1]) / den;
                    double u = cross2(c[0] - a[0], c[1] - a[1], b[0] - a[0], b[1] - a[1]) / den;
                    if (t > 0.001D && t < 0.999D && u >= -0.001D && u <= 1.001D) {
                        ts.add(t);
                    }
                }
            }
            Collections.sort(ts);
            double last = 0.0D;
            for (double t : ts) {
                if (t - last < 0.01D) {
                    continue;
                }
                last = t;
                newPoints.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                boundaryIndices.add(newPoints.size() - 1);
                inserted++;
            }
        }
        newPoints.add(oldPoints.get(oldPoints.size() - 1));
        List<AtlasRoadNode> nodes = new ArrayList<>();
        for (AtlasRoadNode node : road.nodes()) {
            if (AtlasRoadNode.KIND_BOUNDARY.equals(node.kind())) {
                continue;
            }
            double[] point = nodePoint(road, node);
            if (point == null) {
                continue;
            }
            int newIndex = -1;
            double bestNodeDistance = 0.5D;
            for (int i = 0; i < newPoints.size(); i++) {
                double nodeDistance = Math.hypot(newPoints.get(i)[0] - point[0],
                        newPoints.get(i)[1] - point[1]);
                if (nodeDistance <= bestNodeDistance) {
                    bestNodeDistance = nodeDistance;
                    newIndex = i;
                }
            }
            if (newIndex >= 0) {
                nodes.add(node.withIndex(newIndex));
            } else {
                droppedNodes++;
            }
        }
        int added = 0;
        for (int index : boundaryIndices) {
            double[] point = newPoints.get(index);
            boolean exists = false;
            for (AtlasRoadNode node : nodes) {
                double[] other = newPoints.get(Math.min(Math.max(node.index(), 0), newPoints.size() - 1));
                if (Math.hypot(other[0] - point[0], other[1] - point[1]) < 0.5D) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                continue;
            }
            nodes.add(new AtlasRoadNode("b" + index, index, "交界 " + (added + 1),
                    AtlasRoadNode.KIND_BOUNDARY));
            added++;
        }
        AtlasContent.get().replaceRoad(road, road.withPoints(newPoints, nodes));
        AtlasContent.get().saveAuthored();
        AtlasRoad refreshed = AtlasContent.get().roadByKey(road.key());
        selectedRoad = refreshed != null ? refreshed : road;
        selectedNode = null;
        rebuildButtons();
        message("交界断点完成：新增 " + added + " 个交界节点（共插入 " + inserted + " 个顶点）"
                + (droppedNodes > 0 ? "，丢弃 " + droppedNodes + " 个无法重映射的节点" : ""));
    }
}

