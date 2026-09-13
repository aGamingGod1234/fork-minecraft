package dev.fork.gameplay;

import com.google.gson.*;
import dev.fork.core.ForkContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static dev.fork.gameplay.ForkEngine.*;

/** Validated persisted LIVE evidence. Never advances the authoritative engine or invokes providers. */
public record ForkRecordedBundle(String id, String sourceDigest, State initial, State clinicChoice,
        State workshopChoice, List<Receipt> clinic, List<Receipt> workshop, List<Source> sources) {
    public record Source(String file, String sha256) {}
    private record Read<T>(T value, Source source) {}
    public ForkRecordedBundle {
        clinic=List.copyOf(clinic); workshop=List.copyOf(workshop); sources=List.copyOf(sources);
    }
    public static ForkRecordedBundle load(Path directory,Gson json) throws Exception {
        Path root=directory.toRealPath();
        Map<String,List<Receipt>> branches=new HashMap<>();
        try(var paths=Files.list(root)) {
            var files=paths.filter(p->p.getFileName().toString().startsWith("receipt-")&&p.toString().endsWith(".json")).sorted().toList();
            if(files.size()>4096) throw new IllegalStateException("Too many receipts");
            for(var path:files) {
                Receipt r=read(root,path.getFileName().toString(),Receipt.class,json).value();
                if(r.state()!=null && r.state().mode()==Mode.LIVE)
                    branches.computeIfAbsent(r.state().branch(),ignored->new ArrayList<>()).add(r);
            }
        }
        var valid=new ArrayList<List<Receipt>>();
        for(var receipts:branches.values()) {
            receipts.sort(Comparator.comparingInt(r->r.state().round()));
            try { validate(receipts); valid.add(List.copyOf(receipts)); }
            catch(IllegalArgumentException | IllegalStateException ignored) { }
        }
        valid.sort(Comparator.comparingLong((List<Receipt> r)->r.getLast().state().epoch()).reversed());
        for(var b:valid) for(var a:valid) {
            State as=a.getLast().state(),bs=b.getLast().state();
            if(as.allocation()!=Power.CLINIC||bs.allocation()!=Power.WORKSHOP
                    ||bs.epoch()!=as.epoch()+1||!process(as.branch()).equals(process(bs.branch()))) continue;
            var sources=new ArrayList<Source>();
            var archive=read(root,"archive-"+as.branch()+".json",Archive.class,json);
            if(!archive.value().state().equals(as)||!archive.value().receipts().equals(a))
                throw new IllegalStateException("Archive differs from persisted receipts");
            sources.add(archive.source());
            for(var receipt:java.util.stream.Stream.concat(a.stream(),b.stream()).toList()) {
                var saved=read(root,"receipt-"+receipt.ticket().branch()+"-"+receipt.state().round()+".json",Receipt.class,json);
                if(!saved.value().equals(receipt)) throw new IllegalStateException("Receipt identity mismatch");
                sources.add(saved.source());
            }
            boolean initialFound=false;
            try(var paths=Files.list(root)) {
                for(var path:paths.filter(p->p.getFileName().toString().matches("INITIAL-[0-9]+\\.json")).sorted().toList()) {
                    var saved=read(root,path.getFileName().toString(),JsonObject.class,json);
                    State initial=json.fromJson(saved.value().get("initialState"),State.class);
                    if(initial.mode()==Mode.LIVE && initial.epoch()<=as.epoch()
                            &&process(initial.branch()).equals(process(as.branch()))) {
                        if(!ForkContract.SCHEMA.equals(saved.value().get("schema").getAsString())
                                ||!initial.equals(identity(new ForkEngine(Mode.LIVE,()->0L,initial.epoch()).state(),initial)))
                            throw new IllegalStateException("Invalid persisted INITIAL");
                        var manifest=saved.value();
                        if(!manifest.has("cells")||manifest.getAsJsonArray("cells").size()!=4096
                                ||!manifest.has("outsideSentinels")||manifest.getAsJsonArray("outsideSentinels").size()!=6
                                ||!manifest.has("layout")||!ForkContract.SCHEMA.equals(manifest.getAsJsonObject("layout").get("schema").getAsString()))
                            throw new IllegalStateException("Incomplete persisted INITIAL court evidence");
                        sources.add(saved.source()); initialFound=true; break;
                    }
                }
            }
            if(!initialFound) throw new IllegalStateException("Missing persisted LIVE INITIAL manifest");
            String digest=hash(sources.stream().sorted(Comparator.comparing(Source::file))
                    .map(s->s.file()+" "+s.sha256()+"\n").reduce("",String::concat).getBytes(StandardCharsets.UTF_8));
            var replay=new ForkEngine(Mode.LIVE,()->0L,as.epoch()); State initial=identity(replay.state(),as);
            replay.power(Power.CLINIC); State choice=identity(replay.state(),as);
            var other=new ForkEngine(Mode.LIVE,()->0L,bs.epoch()); other.power(Power.WORKSHOP);
            return new ForkRecordedBundle(digest.substring(0,24),digest,initial,choice,identity(other.state(),bs),a,b,sources);
        }
        throw new IllegalStateException("Requires archived LIVE clinic6 then LIVE workshop6 from the same INITIAL");
    }
    private static String process(String branch) {
        if(branch==null||!branch.matches("[a-zA-Z0-9-]{3,128}")||branch.lastIndexOf('-')<1)
            throw new IllegalArgumentException("Invalid branch");
        return branch.substring(0,branch.lastIndexOf('-'));
    }
    private static <T> Read<T> read(Path root,String name,Class<T> type,Gson json) throws Exception {
        Path path=root.resolve(name).normalize();
        if(!path.getParent().equals(root)||Files.isSymbolicLink(path)||!path.toRealPath().getParent().equals(root)
                ||!Files.isRegularFile(path)||Files.size(path)>1_000_000) throw new IllegalArgumentException("Invalid journal source");
        byte[] bytes=Files.readAllBytes(path);
        return new Read<>(Objects.requireNonNull(json.fromJson(new String(bytes,StandardCharsets.UTF_8),type)),new Source(name,hash(bytes)));
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    /** Test seam accepts synthetic inputs. Production loads persisted journals through load(). */
    public static void validate(List<Receipt> receipts) {
        if(receipts.size()!=ForkContract.ROUNDS) throw new IllegalArgumentException("Exactly six receipts required");
        State first=Objects.requireNonNull(receipts.getFirst().state()); process(first.branch());
        if(first.epoch()<0||first.mode()!=Mode.LIVE||first.allocation()==null) throw new IllegalArgumentException("LIVE allocation required");
        var replay=new ForkEngine(Mode.LIVE,()->0L,first.epoch()); var requests=new HashSet<String>();
        for(int index=0;index<receipts.size();index++) {
            Receipt r=Objects.requireNonNull(receipts.get(index));
            State s=Objects.requireNonNull(r.state()); Ticket t=Objects.requireNonNull(r.ticket());
            if(s.mode()!=Mode.LIVE||!s.branch().equals(first.branch())||s.epoch()!=first.epoch()
                    ||s.round()!=index+1||s.revision()!=index+1||s.allocation()!=first.allocation()
                    ||!t.branch().equals(s.branch())||t.epoch()!=s.epoch()||t.round()!=s.round()
                    ||t.baseRevision()!=index||t.requestId()==null||t.requestId().isBlank()||!requests.add(t.requestId()))
                throw new IllegalArgumentException("Branch/epoch/revision/allocation/ticket mismatch");
            if(r.effects().size()!=3) throw new IllegalArgumentException("Three effects required");
            replay.power(s.allocation()); var next=replay.begin();
            var intents=new ArrayList<Intent>(); var roles=EnumSet.noneOf(Role.class);
            for(var effect:r.effects()) {
                if(effect==null||effect.role()==null||!roles.add(effect.role())) throw new IllegalArgumentException("Duplicate role");
                intents.add(new Intent(effect.role(),"verification-"+index+"-"+effect.role(),effect.proposal()));
            }
            Receipt checked=replay.commit(new Batch(next,intents));
            if(!identity(checked.state(),s).equals(s)||!checked.effects().equals(r.effects()))
                throw new IllegalArgumentException("Engine resource/effect/history mismatch at round "+(index+1));
        }
    }
    private static State identity(State s,State identity) {
        return new State(identity.branch(),identity.epoch(),s.round(),s.revision(),Mode.LIVE,s.allocation(),
                s.reroutes(),s.repair(),s.downtime(),s.gridActiveRound(),s.batteries(),s.courierWaypoint(),s.service(),s.allocationHistory());
    }
    public void verifyUnchanged(Path directory,Gson json) throws Exception {
        Path root=directory.toRealPath();
        for(var source:sources) if(!read(root,source.file(),JsonObject.class,json).source().equals(source))
            throw new IllegalStateException("Evidence changed after preparation");
    }
}
