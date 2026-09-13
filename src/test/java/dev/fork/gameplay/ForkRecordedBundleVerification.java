package dev.fork.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static dev.fork.gameplay.ForkEngine.*;

/** Synthetic rule/journal tests are deliberately separate from real LIVE evidence verification. */
public final class ForkRecordedBundleVerification {
    private static final Gson JSON=new Gson();
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    interface Checked { void run() throws Exception; }
    static void rejects(Checked body) throws Exception {
        try { body.run(); } catch(IllegalArgumentException|IllegalStateException exception) { return; }
        throw new AssertionError("Expected validation rejection");
    }
    static Receipt changed(Receipt receipt,Consumer<JsonObject> change) {
        var json=JSON.toJsonTree(receipt).getAsJsonObject();change.accept(json);return JSON.fromJson(json,Receipt.class);
    }
    static void rejectsChanged(List<Receipt> receipts,int index,Consumer<JsonObject> change) throws Exception {
        var changed=new ArrayList<>(receipts);changed.set(index,changed(changed.get(index),change));
        rejects(()->ForkRecordedBundle.validate(changed));
    }
    static void runSix(ForkEngine engine,Power power) {
        engine.power(power);
        for(int round=1;round<=6;round++) {
            var t=engine.begin();
            engine.commit(new Batch(t,List.of(new Intent(Role.MEDIC,t.requestId()+"m","request_spare"),
                    new Intent(Role.ENGINEER,t.requestId()+"e","repair"),
                    new Intent(Role.COURIER,t.requestId()+"c",round==2?"deliver_spare":"wait"))));
        }
    }
    static void writeReceipts(Path path,List<Receipt> receipts) throws Exception {
        for(var receipt:receipts) Files.writeString(path.resolve("receipt-"+receipt.ticket().branch()+"-"+receipt.state().round()+".json"),JSON.toJson(receipt));
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1&&!args[0].equals("--synthetic")) {
            var bundle=ForkRecordedBundle.load(Path.of(args[0]),JSON);
            System.out.println("VALIDATED persisted LIVE A6+B6 bundle="+bundle.id()+" digest="+bundle.sourceDigest());
            System.out.println("A="+bundle.clinic().getLast().state());
            System.out.println("B="+bundle.workshop().getLast().state());
            bundle.verifyUnchanged(Path.of(args[0]),JSON);return;
        }
        var engine=new ForkEngine(Mode.LIVE,()->0L,1);State initial=engine.state();
        runSix(engine,Power.CLINIC);var a=engine.receipts();long epoch=engine.beginRewind();var archive=engine.archives().getLast();
        engine.finishRewind(epoch,true);runSix(engine,Power.WORKSHOP);var b=engine.receipts();
        ForkRecordedBundle.validate(a);ForkRecordedBundle.validate(b);
        rejects(()->ForkRecordedBundle.validate(a.subList(0,5)));
        var doubled=new ArrayList<>(a);doubled.add(a.getLast());rejects(()->ForkRecordedBundle.validate(doubled));
        rejectsChanged(a,0,j->j.getAsJsonObject("state").addProperty("mode","FIXTURE"));
        rejectsChanged(a,1,j->j.getAsJsonObject("state").addProperty("revision",7));
        rejectsChanged(a,2,j->j.getAsJsonObject("ticket").addProperty("epoch",999));
        rejectsChanged(a,3,j->j.getAsJsonObject("ticket").addProperty("requestId",a.getFirst().ticket().requestId()));
        rejectsChanged(a,1,j->j.getAsJsonObject("state").addProperty("allocation","WORKSHOP"));
        rejectsChanged(a,2,j->j.getAsJsonObject("state").addProperty("service","000"));
        rejectsChanged(a,0,j->j.getAsJsonObject("state").getAsJsonArray("batteries").get(0).getAsJsonObject().addProperty("charge",100));
        rejectsChanged(a,2,j->j.getAsJsonArray("effects").get(1).getAsJsonObject().addProperty("effect","repair +1"));
        rejectsChanged(a,2,j->j.getAsJsonArray("effects").get(1).getAsJsonObject().addProperty("role","MEDIC"));
        Path directory=Files.createTempDirectory("fork-SYNTHETIC-journal-");
        var manifest=new JsonObject();manifest.addProperty("schema","fork-1");manifest.add("initialState",JSON.toJsonTree(initial));
        var cells=new JsonArray();for(int i=0;i<4096;i++) cells.add("SYNTHETIC-TEST-CELL");manifest.add("cells",cells);
        var sentinels=new JsonArray();for(int i=0;i<6;i++) sentinels.add("SYNTHETIC-TEST-SENTINEL");manifest.add("outsideSentinels",sentinels);
        var layout=new JsonObject();layout.addProperty("schema","fork-1");manifest.add("layout",layout);
        Files.writeString(directory.resolve("INITIAL-1.json"),JSON.toJson(manifest));writeReceipts(directory,a);writeReceipts(directory,b);
        Files.writeString(directory.resolve("archive-"+archive.state().branch()+".json"),JSON.toJson(archive));
        var bundle=ForkRecordedBundle.load(directory,JSON);bundle.verifyUnchanged(directory,JSON);
        check(bundle.clinic().equals(a)&&bundle.workshop().equals(b),"Exact persisted source receipts retained");
        check(bundle.sources().size()==14,"Twelve receipts, archive and INITIAL have hashes");
        var cues=ForkRecordedTimeline.cues(bundle);
        check(cues.get(ForkRecordedTimeline.indexAt(cues,41_849)).name().equals("clinic-choice"),"No early clinic round");
        check(cues.get(ForkRecordedTimeline.indexAt(cues,41_850)).state().round()==1,"A1 exact cue");
        check(cues.get(ForkRecordedTimeline.indexAt(cues,45_899)).state().round()==6,"A6 before phrase ends");
        check(cues.get(ForkRecordedTimeline.indexAt(cues,51_850)).state().round()==0,"Rewind same initial");
        check(cues.get(ForkRecordedTimeline.indexAt(cues,61_049)).state().equals(b.getLast().state()),"B6 before phrase ends");
        check(cues.get(ForkRecordedTimeline.indexAt(cues,62_050)).state().equals(a.getLast().state()),"Comparison A");
        check(cues.get(ForkRecordedTimeline.indexAt(cues,65_150)).state().equals(b.getLast().state()),"Comparison B");
        Files.writeString(directory.resolve("INITIAL-1.json"),"\n",StandardOpenOption.APPEND);
        rejects(()->bundle.verifyUnchanged(directory,JSON));
        System.out.println("PASS SYNTHETIC TESTS: exact6+6 receipt validation; fixture/incomplete/duplicate/stale/allocation/resource/effect rejection; persisted source hashing; fixed cues; no provider invoked.");
    }
}
