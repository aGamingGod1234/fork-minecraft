package dev.fork.gameplay;

import java.util.*;

/** Exactly 4096 cells, including air; outside sentinels are verified but never written. */
public final class ForkCheckpoint<T> {
    public interface Volume<T> { T read(int x, int y, int z); void write(int x, int y, int z, T value); }
    public record Cell(int x, int y, int z) {}
    private final List<T> cells;
    private final Map<Cell,T> sentinels;
    public ForkCheckpoint(Volume<T> volume) {
        var values = new ArrayList<T>(4096);
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) values.add(Objects.requireNonNull(volume.read(x,y,z)));
        cells = List.copyOf(values);
        var outside = new LinkedHashMap<Cell,T>();
        for (Cell c : List.of(new Cell(-1,0,0),new Cell(16,0,0),new Cell(0,-1,0),new Cell(0,16,0),new Cell(0,0,-1),new Cell(0,0,16))) outside.put(c,volume.read(c.x,c.y,c.z));
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
