package dev.fork.core;

/** Shared immutable contract. Gameplay owns state transitions; world uses relative cells. */
public final class ForkContract {
    public static final String SCHEMA = "fork-1";
    public static final int ROUNDS = 6;
    public static final int VOLUME_SIZE = 16;
    public static final int REPAIRS_FOR_GRID = 3;
    // Preparation may wait for real providers; reserve five seconds for bridge delivery and commit.
    public static final int TOTAL_ATTEMPT_SECONDS = 60;
    public static final int PROVIDER_BUDGET_MS = (TOTAL_ATTEMPT_SECONDS - 5) * 1000;
    public static final String CHECKPOINT = "INITIAL";
    public static final String DIMENSION = "minecraft:overworld";
    public static final int DEFAULT_ORIGIN_X = 0;
    public static final int DEFAULT_ORIGIN_Y = 64;
    public static final int DEFAULT_ORIGIN_Z = 0;
    public record Cell(int x, int y, int z) {}
    public static final Cell MEDIC = new Cell(3, 1, 3);
    public static final Cell ENGINEER = new Cell(12, 1, 3);
    public static final Cell COURIER = new Cell(3, 1, 12);
    public static final Cell DELIVERY = new Cell(4, 1, 3);
    private ForkContract() {}
}
