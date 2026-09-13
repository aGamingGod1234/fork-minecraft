package dev.fork.gameplay;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static dev.fork.gameplay.ForkEngine.*;

public final class ForkEngineVerification {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException | IllegalStateException e) { return; } throw new AssertionError("Expected rejection"); }
    public static void main(String[] args) {
        var time = new AtomicLong();
        var e = new ForkEngine(Mode.FIXTURE, time::get);
        e.power(Power.CLINIC);
        for (int i = 0; i < 6; i++) { var t = e.begin(); e.commit(e.fixture(t)); }
        check(e.state().service().equals("111111") && e.state().charge() == 2 && e.state().repair() == 0 && e.state().downtime() == 0, "A fixture");
        var old = e.receipts().getFirst().ticket();
        long epoch = e.beginRewind(); e.finishRewind(epoch, true);
        check(e.receipt(old) == null && e.archives().size() == 1, "A receipts isolated");
        e.power(Power.WORKSHOP);
        for (int i = 0; i < 6; i++) {
            var t = e.begin(); var batch = e.fixture(t); var r = e.commit(batch);
            check(e.commit(batch).equals(r) && e.state().round() == i + 1, "Idempotent receipt");
            check(e.state().batteries().size() == 2, "Immutable battery identities");
        }
        check(e.state().service().equals("110111") && e.state().charge() == 0 && e.state().repair() == 3 && e.state().downtime() == 1 && e.state().gridActiveRound() == 4, "B fixture");
        rejects(e::begin);
        epoch = e.beginRewind(); e.finishRewind(epoch, true); e.power(Power.WORKSHOP);
        var t = e.begin(); var b = e.fixture(t); var initial = e.state();
        rejects(() -> e.commit(new Batch(b.ticket(), b.intents().subList(0, 2))));
        check(e.state().equals(initial), "Incomplete commits nothing");
        rejects(() -> e.power(Power.CLINIC));
        time.set(20_000_000_000L); rejects(() -> e.commit(b));
        check(e.state().equals(initial), "Timeout commits nothing");
        var waitTicket = e.begin(); var waitBatch = new Batch(waitTicket, List.of(new Intent(Role.MEDIC,"m","destroy"),new Intent(Role.ENGINEER,"e","wait"),new Intent(Role.COURIER,"c","wait")));
        e.commit(waitBatch); check(e.state().round() == 1 && e.state().charge() == 1 && e.state().repair() == 0, "Illegal/wait passive effects");
        e.power(Power.CLINIC); rejects(() -> e.power(Power.WORKSHOP));
        var stale = e.fixture(e.begin()); e.cancel(); rejects(() -> e.commit(stale));
        epoch = e.beginRewind(); e.finishRewind(epoch, true); rejects(() -> e.commit(stale));
        var world = new HashMap<String,String>();
        var volume = new ForkCheckpoint.Volume<String>() {
            public String read(int x,int y,int z) { return world.getOrDefault(x+","+y+","+z,"AIR"); }
            public void write(int x,int y,int z,String value) { check(x>=0&&x<16&&y>=0&&y<16&&z>=0&&z<16,"Outside write"); world.put(x+","+y+","+z,value); }
        };
        world.put("-1,0,0","SENTINEL"); world.put("1,0,1","STONE");
        var checkpoint = new ForkCheckpoint<>(volume);
        for(int restore=0;restore<3;restore++) {
            volume.write(1,0,1,"AIR"); volume.write(15,15,15,"DIRT");
            check(checkpoint.restore(volume)&&volume.read(15,15,15).equals("AIR")&&volume.read(-1,0,0).equals("SENTINEL"),"Bounded air/sentinel restore "+restore);
        }
        world.put("-1,0,0","WRONG"); check(!checkpoint.restore(volume),"Changed sentinel fails restore");
        System.out.println("PASS canonical A/B, next-round grid, conservation, duplicate receipts, incomplete, timeout, illegal wait, reroute, cancel, stale epoch, three 4096-cell AIR/sentinel restores and changed-sentinel rejection");
    }
}
