package dev.fork.integration;

import com.google.gson.*;
import dev.fork.core.ForkContract;
import dev.fork.gameplay.*;
import dev.agaminggod.arenaagents.agent.*;
import dev.agaminggod.arenaagents.server.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** One court, immutable placement, three reused Arena bodies and one server authority. */
public final class ForkSession implements ForkServerAdapter.Court, ForkCheckpoint.Volume<BlockState> {
    public final ForkServerAdapter adapter;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final BlockPos origin;
    private final JsonObject layout;
    private final Path storage;
    private final Map<ForkEngine.Role,AgentId> actors = new EnumMap<>(ForkEngine.Role.class);
    private final Map<AgentId,AgentProfile> profiles = new HashMap<>();
    private final ForkCheckpoint<BlockState> checkpoint;
    private ForkEngine.Ticket lastAttempt;
    private boolean retryUsed;
    private String visualIssue = "";
    private String providerIssue = "";
    private boolean wasPending;
    private int broadcasts;

    public ForkSession(MinecraftServer server, ForkEngine.Mode mode) throws Exception {
        this.server=server; level=server.overworld();
        var manager=CodexAgentManager.get(server);
        if(!manager.records().isEmpty()) throw new IllegalStateException("FORK requires a clean profile with no existing Arena actors");
        String override=System.getProperty("fork.courtFile");
        if(override!=null) layout=JsonParser.parseString(Files.readString(Path.of(override))).getAsJsonObject();
        else try(var input=ForkSession.class.getResourceAsStream("/data/fork/world/court-v1.json")) {
            if(input==null) throw new IllegalStateException("Missing packaged court JSON");
            layout=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if(!ForkContract.SCHEMA.equals(layout.get("schema").getAsString()) || !ForkContract.DIMENSION.equals(layout.get("dimension").getAsString())) throw new IllegalArgumentException("Court schema/dimension mismatch");
        var size=layout.getAsJsonArray("size");
        if(size.size()!=3 || size.get(0).getAsInt()!=16 || size.get(1).getAsInt()!=16 || size.get(2).getAsInt()!=16) throw new IllegalArgumentException("Court must be 16 cubed");
        var o=layout.getAsJsonArray("origin"); origin=new BlockPos(o.get(0).getAsInt(),o.get(1).getAsInt(),o.get(2).getAsInt());
        storage=server.getWorldPath(LevelResource.ROOT).resolve("fork"); Files.createDirectories(storage);
        long epoch=Files.exists(storage.resolve("epoch.txt")) ? Long.parseLong(Files.readString(storage.resolve("epoch.txt")).trim())+1 : 1;
        Files.writeString(storage.resolve("epoch.txt"),Long.toString(epoch));
        adapter=new ForkServerAdapter(new ForkEngine(mode,System::nanoTime,epoch),this);
        // Validate the complete JSON before the first placement, then write only the bounded volume.
        var palette=new ArrayList<BlockState>();
        Set<String> allowed=Set.of("air","smooth_stone","polished_andesite","white_concrete","cyan_concrete","orange_concrete","glass","dark_prismarine","yellow_concrete","sea_lantern","light_gray_concrete");
        for(var entry:layout.getAsJsonArray("palette")) {
            Identifier id=Identifier.parse(entry.getAsString());
            if(!id.getNamespace().equals("minecraft") || !allowed.contains(id.getPath())) throw new IllegalArgumentException("Unapproved court palette");
            palette.add(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
        }
        if(!palette.getFirst().isAir()) throw new IllegalArgumentException("Court palette zero must be AIR");
        var planned=new LinkedHashMap<BlockPos,BlockState>();
        for(var entry:layout.getAsJsonArray("cells")) {
            var c=entry.getAsJsonArray(); int x=c.get(0).getAsInt(),y=c.get(1).getAsInt(),z=c.get(2).getAsInt(),p=c.get(3).getAsInt();
            if(c.size()!=4 || x<0||x>15||y<0||y>15||z<0||z>15||p<0||p>=palette.size() || planned.put(new BlockPos(x,y,z),palette.get(p))!=null) throw new IllegalArgumentException("Invalid court cell");
        }
        var sentinelCells=new ArrayList<ForkCheckpoint.Cell>();
        for(var el:layout.getAsJsonArray("outsideSentinels")) { var c=el.getAsJsonArray(); sentinelCells.add(new ForkCheckpoint.Cell(c.get(0).getAsInt(),c.get(1).getAsInt(),c.get(2).getAsInt())); }
        var outsideBefore=new LinkedHashMap<ForkCheckpoint.Cell,BlockState>();
        for(var c:sentinelCells) outsideBefore.put(c,read(c.x(),c.y(),c.z()));
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) write(x,y,z,Blocks.AIR.defaultBlockState());
        planned.forEach((pos,state)->write(pos.getX(),pos.getY(),pos.getZ(),state));
        projectBlocks(adapter.engine().state());
        if(outsideBefore.entrySet().stream().anyMatch(e->!e.getValue().equals(read(e.getKey().x(),e.getKey().y(),e.getKey().z())))) throw new IllegalStateException("Outside sentinel changed during court placement");
        checkpoint=new ForkCheckpoint<>(this,sentinelCells);
        for(var role:ForkEngine.Role.values()) {
            var c=anchor(role,false);
            // Reuse Arena's narrowly scoped offline body lifecycle without registering autonomous goals.
            // Fixture bodies need no provider catalog; Live proposals use the separate bounded batch path.
            var id=new AgentId(new UUID(0x464f524b00000000L,role.ordinal()+1));
            var profile=new AgentProfile("codex","gpt-5.6-luna","xhigh","priority",Optional.of("FORK_"+role.name()),role.ordinal(),AgentGameMode.ADVENTURE);
            profiles.put(id,profile); actors.put(role,id);
            if(OfflineAgentPlayers.find(server,id,profile).isEmpty())
                OfflineAgentPlayers.spawn(server,id,profile,new Vec3(origin.getX()+c.x()+0.5,origin.getY()+c.y(),origin.getZ()+c.z()+0.5),180,0,level.dimension(),AgentGameMode.ADVENTURE);
        }
        JsonObject manifest=new JsonObject(); manifest.addProperty("schema",ForkContract.SCHEMA);
        manifest.add("layout",layout.deepCopy()); manifest.add("initialState",ForkEntrypoint.JSON.toJsonTree(adapter.engine().state()));
        manifest.add("actors",ForkEntrypoint.JSON.toJsonTree(actors));
        manifest.add("cells",ForkEntrypoint.JSON.toJsonTree(checkpoint.cells().stream().map(Object::toString).toList()));
        manifest.add("outsideSentinels",ForkEntrypoint.JSON.toJsonTree(outsideBefore.entrySet().stream().map(e->Map.of("cell",e.getKey(),"state",e.getValue().toString())).toList()));
        Files.writeString(storage.resolve("INITIAL-"+epoch+".json"),ForkEntrypoint.JSON.toJson(manifest),StandardOpenOption.CREATE_NEW);
    }
    public BlockState read(int x,int y,int z) { return level.getBlockState(origin.offset(x,y,z)); }
    public void write(int x,int y,int z,BlockState value) {
        if(x<0||x>15||y<0||y>15||z<0||z>15) throw new IllegalArgumentException("Outside court write");
        level.setBlock(origin.offset(x,y,z),value,2|16);
    }
    private ForkContract.Cell anchor(ForkEngine.Role role,boolean delivered) {
        return switch(role) { case MEDIC -> ForkContract.MEDIC; case ENGINEER -> ForkContract.ENGINEER; case COURIER -> delivered ? ForkContract.DELIVERY : ForkContract.COURIER; };
    }
    private Optional<net.minecraft.server.level.ServerPlayer> body(AgentId id) { return OfflineAgentPlayers.find(server,id,profiles.get(id)); }
    public boolean ready() { return actors.size()==3 && actors.values().stream().allMatch(id->body(id).isPresent()); }
    @Override public void project(ForkEngine.State state) {
        try { projectBlocks(state); settleActors(state); visualIssue=""; }
        catch(Exception e) { visualIssue="Visual projection failed: "+e.getMessage(); }
    }
    private void projectBlocks(ForkEngine.State s) {
        boolean grid=s.gridActiveRound()>0 && s.round()>=s.gridActiveRound();
        boolean clinic=s.round()==0 || s.service().endsWith("1");
        write(3,5,3,(clinic?Blocks.SEA_LANTERN:Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState());
        write(12,5,3,(grid||s.allocation()==ForkEngine.Power.WORKSHOP?Blocks.SEA_LANTERN:Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState());
        for(int i=0;i<3;i++) write(11+i,0,4,(s.repair()>i?Blocks.LIME_CONCRETE:Blocks.ORANGE_CONCRETE).defaultBlockState());
        var b0=s.batteries().get(0); var b1=s.batteries().get(1);
        write(5,2,3,(b0.charge()>0?Blocks.YELLOW_CONCRETE:Blocks.GRAY_CONCRETE).defaultBlockState());
        write(5,2,4,(b1.holder().startsWith("clinic")?(b1.charge()>0?Blocks.YELLOW_CONCRETE:Blocks.GRAY_CONCRETE):Blocks.AIR).defaultBlockState());
        write(3,1,13,(b1.holder().equals("courier")?Blocks.YELLOW_CONCRETE:Blocks.AIR).defaultBlockState());
    }
    private void settleActors(ForkEngine.State state) {
        var manager=CodexAgentManager.get(server);
        for(var entry:actors.entrySet()) body(entry.getValue()).ifPresent(player->{
            var c=anchor(entry.getKey(),state.courierWaypoint().equals("clinic"));
            OfflineAgentPlayers.stop(player);
            player.setInvulnerable(true); player.setGameMode(GameType.ADVENTURE); player.getInventory().clearContent();
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(level,origin.getX()+c.x()+0.5,origin.getY()+c.y(),origin.getZ()+c.z()+0.5,Set.of(),180,0,true);
        });
    }
    public String advance(boolean retry) {
        if(!ready()) throw new IllegalStateException("Waiting for all three Arena bodies; no round committed");
        var e=adapter.engine();
        if(e.pending()!=null) throw new IllegalStateException("Round pending; cancel remains available");
        boolean previousFailed=lastAttempt!=null && lastAttempt.baseRevision()==e.state().revision() && lastAttempt.epoch()==e.state().epoch();
        if(previousFailed && (!retry || retryUsed)) throw new IllegalStateException("Use one explicit /fork retry, otherwise rewind");
        if(retry && !previousFailed) throw new IllegalStateException("No failed attempt to retry");
        retryUsed=previousFailed; providerIssue="";
        if(e.state().mode()==ForkEngine.Mode.FIXTURE) {
            var receipt=adapter.advanceFixture(); persist(receipt); lastAttempt=null; retryUsed=false; return summary();
        }
        lastAttempt=e.begin(retry); wasPending=true;
        JsonObject payload=new JsonObject(); payload.add("ticket",ForkEntrypoint.JSON.toJsonTree(lastAttempt));
        payload.add("state",ForkEntrypoint.JSON.toJsonTree(e.state())); payload.addProperty("budgetMs",20_000);
        if(!CodexAgentServerRuntime.sendFork(server,"fork_request",payload)) { e.cancel(); throw new IllegalStateException("Approved coordinator offline; committed nothing"); }
        return summary();
    }
    public void cancel() {
        adapter.engine().cancel();
        for(var id:actors.values()) body(id).ifPresent(OfflineAgentPlayers::stop);
        JsonObject p=new JsonObject(); p.addProperty("epoch",adapter.engine().state().epoch());
        CodexAgentServerRuntime.sendFork(server,"fork_cancel",p);
    }
    public JsonObject accept(JsonObject payload) {
        try {
            if(payload.has("error")) {
                var t=ForkWire.ticket(payload.getAsJsonObject("ticket"));
                if(t.equals(adapter.engine().pending())) { adapter.engine().cancel(); providerIssue=payload.get("error").getAsString(); }
                return result("error",providerIssue);
            }
            var batch=ForkWire.batch(payload); var receipt=adapter.commit(batch); persist(receipt);
            lastAttempt=null; retryUsed=false; broadcast(summary());
            return ForkEntrypoint.JSON.toJsonTree(receipt).getAsJsonObject();
        } catch(Exception e) { return result("rejected",e.getMessage()); }
    }
    private JsonObject result(String status,String message) { var r=new JsonObject();r.addProperty("status",status);r.addProperty("message",message);return r; }
    private void persist(ForkEngine.Receipt receipt) {
        try { Path p=storage.resolve("receipt-"+receipt.ticket().branch()+"-"+receipt.state().round()+".json");
            String value=ForkEntrypoint.JSON.toJson(receipt);
            if(!Files.exists(p)) Files.writeString(p,value,StandardOpenOption.CREATE_NEW);
            else if(!Files.readString(p).equals(value)) throw new IllegalStateException("Receipt persistence mismatch");
        } catch(Exception ex) { adapter.engine().pause(); providerIssue="Receipt persistence failed; paused: "+ex.getMessage(); }
    }
    @Override public void detachOldWork(long epoch) {
        cancel();
        try { Files.writeString(storage.resolve("epoch.txt"),Long.toString(epoch));
            var archives=adapter.engine().archives(); var archive=archives.getLast();
            Path archivePath=storage.resolve("archive-"+archive.state().branch()+".json");
            if(!Files.exists(archivePath)) Files.writeString(archivePath,ForkEntrypoint.JSON.toJson(archive),StandardOpenOption.CREATE_NEW);
        } catch(Exception e) { throw new IllegalStateException("Epoch/archive persistence failed",e); }
        lastAttempt=null; retryUsed=false;
        // These bodies are created without goals. Ordinary Arena actions are fenced for this entire session.
        settleActors(adapter.engine().state());
    }
    @Override public boolean restoreAndVerifyInitial() {
        if(!ready()) return false;
        boolean blocks=checkpoint.restore(this);
        for(var entry:actors.entrySet()) {
            var player=body(entry.getValue()).orElseThrow(); var c=anchor(entry.getKey(),false);
            player.teleportTo(level,origin.getX()+c.x()+0.5,origin.getY()+c.y(),origin.getZ()+c.z()+0.5,Set.of(),180,0,true);
            player.getInventory().clearContent(); player.setInvulnerable(true);
            if(player.level()!=level || player.position().distanceToSqr(new Vec3(origin.getX()+c.x()+0.5,origin.getY()+c.y(),origin.getZ()+c.z()+0.5))>0.001) return false;
        }
        return blocks && actors.size()==3 && checkpoint.verify(this);
    }
    public String demolish() {
        if(adapter.engine().pending()!=null) throw new IllegalStateException("Cancel the pending round first");
        adapter.engine().pause(); var cells=layout.getAsJsonArray("demolitionCells");
        if(cells.size()>64) throw new IllegalStateException("Demolition exceeds 64 cells");
        for(var el:cells) { var c=el.getAsJsonArray(); int x=c.get(0).getAsInt(),y=c.get(1).getAsInt(),z=c.get(2).getAsInt();
            if(y<6) throw new IllegalStateException("Demolition cannot touch ground/stations");
            level.levelEvent(2001,origin.offset(x,y,z),net.minecraft.world.level.block.Block.getId(read(x,y,z)));
            write(x,y,z,Blocks.AIR.defaultBlockState()); }
        return "Paused. "+cells.size()+" facade cells removed. /fork rewind required before advance.";
    }
    public void tick() {
        var e=adapter.engine(); boolean pending=e.pending()!=null;
        if(wasPending && !pending && lastAttempt!=null && lastAttempt.baseRevision()==e.state().revision()) { cancel(); providerIssue="Attempt ended without commit; one explicit retry or rewind"; broadcast(summary()); }
        wasPending=pending;
        settleActors(e.state());
        level.setDayTime(6000); level.setWeatherParameters(6000,0,false,false);
        for(var p:server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.ADVENTURE); p.getInventory().clearContent(); p.setInvulnerable(true);
            if(++broadcasts%40==0) p.displayClientMessage(Component.literal(summary()),true);
        }
    }
    private void broadcast(String text) { server.getPlayerList().broadcastSystemMessage(Component.literal(text),false); }
    private String describe(ForkEngine.State s) {
        return s.mode()+" | round "+s.round()+"/6 | service "+s.service()+"_".repeat(6-s.round())+" | downtime "+s.downtime()+" | repair "+s.repair()+"/3 | grid "+(s.gridActiveRound()==0?"inactive":"round "+s.gridActiveRound())+" | charge "+s.charge()+" | "+s.allocation();
    }
    public String summary() { var e=adapter.engine(); return "FORK "+describe(e.state())+" | "+(e.paused()?"PAUSED":e.pending()!=null?"PENDING":e.state().complete()?"COMPLETE":"READY")+(visualIssue.isEmpty()?"":" | "+visualIssue)+(providerIssue.isEmpty()?"":" | "+providerIssue); }
    public String inspect() {
        var receipts=adapter.engine().receipts();StringBuilder text=new StringBuilder(summary());
        if(!receipts.isEmpty()) for(var effect:receipts.getLast().effects()) text.append("\n").append(effect.role()).append(": ").append(effect.proposal()).append(" | ").append(effect.validation()).append(" | ").append(effect.effect());
        return text+"\nallocation: "+adapter.engine().state().allocationHistory();
    }
    public String compare() {
        var e=adapter.engine(); if(e.archives().isEmpty()) throw new IllegalStateException("Complete A then rewind and complete B");
        var a=e.archives().stream().filter(x->x.state().complete()).findFirst().orElseThrow(()->new IllegalStateException("No complete archived branch"));
        if(!e.state().complete()) throw new IllegalStateException("Comparison requires equal six-round runs");
        return "A: "+describe(a.state())+"\nB: "+describe(e.state())+"\nA allocation: "+a.state().allocationHistory()+"\nB allocation: "+e.state().allocationHistory();
    }
}
