package dev.fork.integration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.fork.gameplay.ForkEngine;
import dev.agaminggod.arenaagents.server.GoalControl;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Dedicated FORK commands reuse Minecraft's readable command console. */
public final class ForkEntrypoint implements ModInitializer {
    private static final Map<MinecraftServer,ForkSession> SESSIONS = new WeakHashMap<>();
    public static final Gson JSON = new Gson();
    public static ForkSession session(MinecraftServer server) { return SESSIONS.get(server); }
    public static boolean active(MinecraftServer server) { return SESSIONS.containsKey(server); }
    @Override public void onInitialize() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(ForkStatusPayload.TYPE,ForkStatusPayload.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var root = Commands.literal("fork").requires(GoalControl::mayControl)
                .executes(c -> help(c.getSource()));
            var start = Commands.literal("start");
            for (var mode : List.of(ForkEngine.Mode.FIXTURE,ForkEngine.Mode.LIVE))
                start.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(c -> run(c.getSource(), () -> {
                    if (active(c.getSource().getServer())) throw new IllegalStateException("FORK already started; use rewind");
                    if (ForkSession.hasSavedRun(c.getSource().getServer())) throw new IllegalStateException("Saved FORK evidence preserved. Use /fork new fixture or /fork new live for an explicit fresh session; this does not resume the old round.");
                    var s = new ForkSession(c.getSource().getServer(), mode);
                    SESSIONS.put(c.getSource().getServer(), s); return s.summary();
                })));
            root.then(start);
            var fresh=Commands.literal("new");
            for(var mode:List.of(ForkEngine.Mode.FIXTURE,ForkEngine.Mode.LIVE)) fresh.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(c->run(c.getSource(),()->{
                if(active(c.getSource().getServer())) throw new IllegalStateException("A session is active; use rewind. New-session reset is available after a stopped-world restart.");
                var s=new ForkSession(c.getSource().getServer(),mode);SESSIONS.put(c.getSource().getServer(),s);
                return "Explicit NEW session. Earlier receipts and archives remain on disk; fresh epoch/context. "+s.summary();
            })));
            root.then(fresh);
            for (var power : ForkEngine.Power.values())
                root.then(Commands.literal("power").then(Commands.literal(power.name().toLowerCase(Locale.ROOT))
                    .executes(c -> run(c.getSource(), () -> { var s=requireCourt(c.getSource()); s.adapter.engine().power(power); return s.summary(); }))));
            root.then(Commands.literal("advance").executes(c -> run(c.getSource(), () -> requireCourt(c.getSource()).advance(false))));
            root.then(Commands.literal("retry").executes(c -> run(c.getSource(), () -> requireCourt(c.getSource()).advance(true))));
            root.then(Commands.literal("cancel").executes(c -> run(c.getSource(), () -> { var s=require(c.getSource()); s.requireNoPresentation(); s.cancel(); return s.summary(); })));
            root.then(Commands.literal("recover").executes(c -> run(c.getSource(), () -> require(c.getSource()).recoverBodies())));
            root.then(Commands.literal("rewind").executes(c -> run(c.getSource(), () -> require(c.getSource()).rewind(c.getSource().getPlayerOrException()))));
            root.then(Commands.literal("locator").executes(c -> run(c.getSource(), () -> require(c.getSource()).locator())));
            root.then(Commands.literal("explore").executes(c -> run(c.getSource(), () -> require(c.getSource()).explore(c.getSource().getPlayerOrException()))));
            root.then(Commands.literal("return").executes(c -> run(c.getSource(), () -> require(c.getSource()).returnToCourt(c.getSource().getPlayerOrException()))));
            root.then(Commands.literal("visit").then(Commands.argument("place",com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> run(c.getSource(), () -> require(c.getSource()).visit(c.getSource().getPlayerOrException(),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"place"))))));
            root.then(Commands.literal("inspect").executes(c -> run(c.getSource(), () -> require(c.getSource()).inspect())));
            root.then(Commands.literal("compare").executes(c -> run(c.getSource(), () -> require(c.getSource()).compare())));
            root.then(Commands.literal("demolish").executes(c -> run(c.getSource(), () -> requireCourt(c.getSource()).demolish())));
            root.then(Commands.literal("presentation")
                .then(Commands.literal("prepare").executes(c->runQuiet(c.getSource(),()->require(c.getSource()).preparePresentation(c.getSource().getPlayerOrException()))))
                .then(Commands.literal("start").then(Commands.argument("bundle",com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c->runQuiet(c.getSource(),()->require(c.getSource()).startPresentation(c.getSource().getPlayerOrException(),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"bundle"))))))
                .then(Commands.literal("stop").executes(c->runQuiet(c.getSource(),()->require(c.getSource()).stopPresentation(c.getSource().getPlayerOrException())))));
            dispatcher.register(root);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try { ForkProjectionRestore.recover(server); }
            catch(Exception e) { System.err.println("FORK recorded presentation recovery required: "+e.getMessage()); }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            server.execute(()->{
                try { var s=session(server); if(s==null) ForkProjectionRestore.recover(server); else s.recoverPresentationOwner(handler.getPlayer()); }
                catch(Exception e) { System.err.println("FORK recorded owner recovery required: "+e.getMessage()); }
            });
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { var s=session(server); if(s!=null) s.tick(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { var s=session(server); if(s!=null) s.stop(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.remove(server));
        UseBlockCallback.EVENT.register((player,world,hand,hit) -> world instanceof net.minecraft.server.level.ServerLevel l && active(l.getServer()) && !session(l.getServer()).mayExplorePlace(player,hand,hit) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player,world,hand) -> world instanceof net.minecraft.server.level.ServerLevel l && active(l.getServer()) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player,world,hand,entity,hit) -> world instanceof net.minecraft.server.level.ServerLevel l && active(l.getServer()) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player,world,hand,entity,hit) -> world instanceof net.minecraft.server.level.ServerLevel l && active(l.getServer()) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player,world,hand,pos,direction) -> world instanceof net.minecraft.server.level.ServerLevel l && active(l.getServer()) && !session(l.getServer()).mayExploreEdit(player,pos) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((world,player,pos,state,entity) -> !active(world.getServer()) || session(world.getServer()).mayExploreEdit(player,pos));
    }
    private static ForkSession require(CommandSourceStack source) {
        var s=session(source.getServer()); if(s==null) throw new IllegalStateException("Use /fork start fixture or /fork start live"); return s;
    }
    private static ForkSession requireCourt(CommandSourceStack source) throws Exception {
        var s=require(source);s.requireCourt(source.getPlayerOrException());return s;
    }
    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("FORK | start fixture/live (first run) | new fixture/live (explicit restart) | power clinic/workshop | advance | retry | cancel | rewind | recover | inspect | compare | demolish | explore (fly/build city) | return"), false); return 1;
    }
    private interface Operation { String run() throws Exception; }
    private static int runQuiet(CommandSourceStack source,Operation action) {
        try { action.run(); return 1; }
        catch(Exception e) {
            var s=session(source.getServer());if(s!=null&&source.getPlayer()!=null) s.presentationFailed(source.getPlayer(),e.getMessage());
            source.sendFailure(Component.literal("FORK presentation: "+e.getMessage())); return 0;
        }
    }
    private static int run(CommandSourceStack source, Operation action) {
        try { String text=action.run(); source.sendSuccess(() -> Component.literal(text), true); return 1; }
        catch(Exception e) { source.sendFailure(Component.literal("FORK: " + e.getMessage())); return 0; }
    }
    public static JsonObject accept(MinecraftServer server, JsonObject payload) {
        var s=session(server); if(s==null) throw new IllegalStateException("FORK not started");
        return s.accept(payload);
    }
}
