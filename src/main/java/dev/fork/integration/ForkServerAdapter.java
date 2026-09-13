package dev.fork.integration;

import dev.fork.gameplay.ForkEngine;

/** Concrete server seam: invoke on the Minecraft server thread; project only committed state. */
public final class ForkServerAdapter {
    public interface Court {
        void project(ForkEngine.State state);
        void detachOldWork(long epoch);
        boolean restoreAndVerifyInitial();
    }
    private final ForkEngine engine;
    private final Court court;
    public ForkServerAdapter(ForkEngine engine, Court court) { this.engine = engine; this.court = court; }
    public ForkEngine engine() { return engine; }
    public ForkEngine.Receipt commit(ForkEngine.Batch batch) {
        var receipt = engine.commit(batch);
        // Historical receipts acknowledge old calls; presentation always reflects current authority.
        court.project(engine.state());
        return receipt;
    }
    public ForkEngine.Receipt advanceFixture() {
        var ticket = engine.begin();
        return commit(engine.fixture(ticket));
    }
    public void rewind() {
        long epoch = engine.beginRewind();
        court.detachOldWork(epoch);
        engine.finishRewind(epoch, court.restoreAndVerifyInitial());
        court.project(engine.state());
    }
}
