package dev.fork.gameplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual committed branch results, held without pausing the integrated server. */
public final class ForkFilmScreen extends Screen {
    private final ForkEngine.State a,b;
    private final boolean prepared;
    public ForkFilmScreen(ForkEngine.State a,ForkEngine.State b){this(a,b,false);}
    public ForkFilmScreen(ForkEngine.State a,ForkEngine.State b,boolean prepared){super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("FORK LIVE results"));this.a=a;this.b=b;this.prepared=prepared;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
        g.fill(0,0,width,height,0xee09131d);
        int x=Math.max(14,(width-390)/2),y=Math.max(20,(height-180)/2);
        g.text(font,b==null?"FORK / CLINIC BRANCH COMPLETE":"FORK / TWO REAL LIVE BRANCHES",x,y,0xff69dfcd,false);
        g.text(font,"Six committed rounds per branch. Actual results below.",x,y+22,0xffffffff,false);
        renderBranch(g,"A / CLINIC",a,x,y+50);
        if(b!=null)renderBranch(g,"B / WORKSHOP",b,x,y+106);
        g.text(font,prepared?"Previously prepared real LIVE results":b==null?"Rewinding the court next...":"Singapore Explore follows automatically...",x,y+172,0xffb8c8d1,false);
    }
    private void renderBranch(GuiGraphicsExtractor g,String title,ForkEngine.State s,int x,int y){
        if(s==null)return;
        g.text(font,title+"  |  "+s.mode()+"  |  "+s.round()+"/6",x,y,0xffffffff,false);
        g.text(font,"Service: "+s.service()+"   Downtime: "+s.downtime()+"   Repair: "+s.repair()+"/3",x,y+16,0xffd1e4ec,false);
        g.text(font,"Charge: "+s.charge()+"   Grid: "+(s.gridActiveRound()==0?"inactive":"round "+s.gridActiveRound()),x,y+32,0xffd1e4ec,false);
    }
}