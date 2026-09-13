package dev.fork.gameplay;

import java.util.*;

/** Exactly 4096 cells, including air; outside sentinels are verified but never written. */
public final class ForkCheckpoint<T> {
    public interface Volume<T> { T read(int x, int y, int z); void write(int x, int y, int z, T value); }
    public record Cell(int x, int y, int z) {}
    private final List<T> cells;
    private final Map<Cell,T> sentinels;
    public ForkCheckpoint(Volume<T> volume) {
        this(volume, List.of(new Cell(-1,0,0),new Cell(16,0,15),new Cell(0,-1,0),new Cell(15,16,15),new Cell(0,0,-1),new Cell(15,0,16)));
    }
    public ForkCheckpoint(Volume<T> volume, List<Cell> sentinelCells) {
        var values = new ArrayList<T>(4096);
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) values.add(Objects.requireNonNull(volume.read(x,y,z)));
        cells = List.copyOf(values);
        var outside = new LinkedHashMap<Cell,T>();
        if(sentinelCells.size()!=6 || new HashSet<>(sentinelCells).size()!=6) throw new IllegalArgumentException("Six unique sentinels required");
        for (Cell c : sentinelCells) {
            if(c.x>=0&&c.x<16&&c.y>=0&&c.y<16&&c.z>=0&&c.z<16) throw new IllegalArgumentException("Sentinel must be outside");
            outside.put(c,volume.read(c.x,c.y,c.z));
        }
        sentinels = Map.copyOf(outside);
    }
    public List<T> cells() { return cells; }
    public boolean restore(Volume<T> volume) {
        int i=0;
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) volume.write(x,y,z,cells.get(i++));
        return verify(volume);
    }
    public boolean verify(Volume<T> volume) {
        int i=0;
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) if (!Objects.equals(cells.get(i++),volume.read(x,y,z))) return false;
        return sentinels.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(),volume.read(e.getKey().x,e.getKey().y,e.getKey().z)));
    }
}
