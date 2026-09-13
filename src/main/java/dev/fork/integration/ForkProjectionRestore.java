package dev.fork.integration;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.fork.gameplay.ForkCheckpoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

/** Durable bounded restoration. Outside sentinels are read and verified, never written. */
final class ForkProjectionRestore {
    private final ForkCheckpoint<BlockState> snapshot;
    private final Path path;
    private ForkProjectionRestore(ForkCheckpoint<BlockState> snapshot,Path path) { this.snapshot=snapshot;this.path=path; }
    static ForkProjectionRestore save(ForkSession court,BlockPos origin,List<ForkCheckpoint.Cell> sentinels,Path storage,
            net.minecraft.server.level.ServerPlayer owner,BlockPos buildCell) throws Exception {
        Path path=storage.resolve("presentation-restore.json");
        if(Files.exists(path)) throw new IllegalStateException("Unrestored presentation journal exists; restart to recover");
        var snapshot=new ForkCheckpoint<BlockState>(court,sentinels);
        var root=new JsonObject();root.addProperty("schema","fork-presentation-restore-1");
        root.add("origin",ForkEntrypoint.JSON.toJsonTree(List.of(origin.getX(),origin.getY(),origin.getZ())));
        var cells=new JsonArray();
        for(var state:snapshot.cells()) cells.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());
        root.add("cells",cells);
        root.add("actors",court.recordedActorPoses());
        var outside=new JsonArray();
        for(var cell:sentinels) {
            var item=new JsonObject(); item.add("cell",ForkEntrypoint.JSON.toJsonTree(cell));
            item.add("state",BlockState.CODEC.encodeStart(JsonOps.INSTANCE,court.read(cell.x(),cell.y(),cell.z())).getOrThrow()); outside.add(item);
        }
        root.add("sentinels",outside);
        var human=new JsonObject();human.addProperty("uuid",owner.getUUID().toString());
        human.addProperty("mode",owner.gameMode.getGameModeForPlayer().getName());
        human.addProperty("selectedSlot",owner.getInventory().getSelectedSlot());
        human.addProperty("x",owner.getX());human.addProperty("y",owner.getY());human.addProperty("z",owner.getZ());
        human.addProperty("yaw",owner.getYRot());human.addProperty("pitch",owner.getXRot());
        var inventory=new JsonArray();
        for(int slot=0;slot<owner.getInventory().getContainerSize();slot++) {
            var stack=owner.getInventory().getItem(slot);
            if(!stack.isEmpty()) { var item=new JsonObject();item.addProperty("slot",slot);
                item.add("stack",net.minecraft.world.item.ItemStack.CODEC.encodeStart(net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,owner.level().registryAccess()),stack).getOrThrow());inventory.add(item); }
        }
        human.add("inventory",inventory);root.add("owner",human);
        root.add("buildCell",ForkEntrypoint.JSON.toJsonTree(List.of(buildCell.getX(),buildCell.getY(),buildCell.getZ())));
        root.add("buildOriginal",BlockState.CODEC.encodeStart(JsonOps.INSTANCE,court.buildState(buildCell)).getOrThrow());
        Path temporary=storage.resolve("presentation-restore.pending");
        Files.writeString(temporary,ForkEntrypoint.JSON.toJson(root),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);
        return new ForkProjectionRestore(snapshot,path);
    }
    void restore(ForkSession court,MinecraftServer server) throws Exception {
        if(!snapshot.restore(court)) throw new IllegalStateException("Presentation restoration failed 4096-cell/sentinel verification");
        recover(server);
    }
    static void recover(MinecraftServer server) throws Exception {
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("fork/presentation-restore.json");
        if(!Files.exists(path)) return;
        if(Files.size(path)>2_000_000) throw new IllegalStateException("Oversized presentation restore journal");
        var document=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if(!"fork-presentation-restore-1".equals(document.get("schema").getAsString())) throw new IllegalStateException("Unknown restore journal");
        var level=server.overworld();
        if(!document.has("worldRestored")||!document.get("worldRestored").getAsBoolean()) {
        var at=document.getAsJsonArray("origin"); var origin=new BlockPos(at.get(0).getAsInt(),at.get(1).getAsInt(),at.get(2).getAsInt());
        if(at.size()!=3||origin.getY()<level.getMinY()||origin.getY()+15>=level.getMaxY()
                ||!level.getWorldBorder().isWithinBounds(origin)||!level.getWorldBorder().isWithinBounds(origin.offset(15,15,15)))
            throw new IllegalStateException("Invalid restore origin");
        var raw=document.getAsJsonArray("cells");
        if(raw.size()!=4096) throw new IllegalStateException("Restore requires exactly4096 cells");
        var cells=new ArrayList<BlockState>();
        for(var item:raw) cells.add(BlockState.CODEC.parse(JsonOps.INSTANCE,item).getOrThrow());
        var sentinels=new LinkedHashMap<ForkCheckpoint.Cell,BlockState>();
        for(var item:document.getAsJsonArray("sentinels")) {
            var object=item.getAsJsonObject();var cell=ForkEntrypoint.JSON.fromJson(object.get("cell"),ForkCheckpoint.Cell.class);
            if(cell.x()>=0&&cell.x()<16&&cell.y()>=0&&cell.y()<16&&cell.z()>=0&&cell.z()<16)
                throw new IllegalStateException("Restore sentinel inside court");
            sentinels.put(cell,BlockState.CODEC.parse(JsonOps.INSTANCE,object.get("state")).getOrThrow());
        }
        if(sentinels.size()!=6) throw new IllegalStateException("Restore requires six sentinels");
        for(var entry:sentinels.entrySet()) {
            var c=entry.getKey();
            if(!level.getBlockState(origin.offset(c.x(),c.y(),c.z())).equals(entry.getValue()))
                throw new IllegalStateException("Restore sentinel changed; journal retained");
        }
        int index=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            level.setBlock(origin.offset(x,y,z),cells.get(index++),2|16);
        index=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            if(!level.getBlockState(origin.offset(x,y,z)).equals(cells.get(index++)))
                throw new IllegalStateException("Crash restoration cell verification failed; journal retained");
        var build=document.getAsJsonArray("buildCell");
        var buildCell=new BlockPos(build.get(0).getAsInt(),build.get(1).getAsInt(),build.get(2).getAsInt());
        if(!buildCell.equals(new BlockPos(446,1,425))) throw new IllegalStateException("Unknown leased build cell");
        var original=BlockState.CODEC.parse(JsonOps.INSTANCE,document.get("buildOriginal")).getOrThrow();
        if(!original.isAir()) throw new IllegalStateException("Build lease original must be air");
        level.setBlock(buildCell,original,2|16);
        if(!level.getBlockState(buildCell).equals(original)) throw new IllegalStateException("Build lease restoration failed");
        for(var entry:sentinels.entrySet()) {
            var c=entry.getKey();
            if(!level.getBlockState(origin.offset(c.x(),c.y(),c.z())).equals(entry.getValue()))
                throw new IllegalStateException("Sentinel changed during restoration");
        }
        var savedActors=document.getAsJsonArray("actors");
        if(savedActors.size()!=3) throw new IllegalStateException("Three saved role poses required");
        for(var item:savedActors) {
            var pose=item.getAsJsonObject();var id=UUID.fromString(pose.get("uuid").getAsString());
            var actor=server.getPlayerList().getPlayer(id);
            if(actor!=null) {
                actor.teleportTo(level,pose.get("x").getAsDouble(),pose.get("y").getAsDouble(),pose.get("z").getAsDouble(),
                        Set.of(),pose.get("yaw").getAsFloat(),pose.get("pitch").getAsFloat(),true);
                actor.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
        }
        server.saveEverything(true,true,true);
        document.addProperty("worldRestored",true);
        Path temporary=path.resolveSibling("presentation-restore.recovered.pending");
        Files.writeString(temporary,ForkEntrypoint.JSON.toJson(document),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        var savedOwner=document.getAsJsonObject("owner");
        var owner=server.getPlayerList().getPlayer(UUID.fromString(savedOwner.get("uuid").getAsString()));
        if(owner==null) return; // Keep the durable restore record until the owning human reconnects.
        owner.getInventory().clearContent();
        for(var item:savedOwner.getAsJsonArray("inventory")) {
            var entry=item.getAsJsonObject();int slot=entry.get("slot").getAsInt();
            if(slot<0||slot>=owner.getInventory().getContainerSize()) throw new IllegalStateException("Invalid saved inventory slot");
            owner.getInventory().setItem(slot,net.minecraft.world.item.ItemStack.CODEC.parse(net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,level.registryAccess()),entry.get("stack")).getOrThrow());
        }
        owner.getInventory().setSelectedSlot(savedOwner.get("selectedSlot").getAsInt());
        owner.setGameMode(net.minecraft.world.level.GameType.byName(savedOwner.get("mode").getAsString()));
        owner.teleportTo(level,savedOwner.get("x").getAsDouble(),savedOwner.get("y").getAsDouble(),savedOwner.get("z").getAsDouble(),
                Set.of(),savedOwner.get("yaw").getAsFloat(),savedOwner.get("pitch").getAsFloat(),true);
        owner.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);owner.containerMenu.broadcastChanges();
        server.getPlayerList().saveAll();
        Files.delete(path);
        System.out.println("FORK restored authoritative court after interrupted recorded presentation;4096 cells and6 sentinels verified.");
    }
}
