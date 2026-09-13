package dev.agaminggod.arenaagents.world;

public final class WorldMutationRevisionsVerification {
	private WorldMutationRevisionsVerification() {
	}

	public static void main(String[] args) {
		System.out.println("Regional mutation assertions passed: " + verify());
	}

	public static int verify() {
		WorldMutationRevisions revisions = new WorldMutationRevisions();
		for (int index = 0; index < 1_000; index++) revisions.recordMutation(index * 512, 0);
		require(revisions.retainedRegions() == 0, "unobserved world activity does not grow the index");
		long initial = revisions.revision(0, 0, 257);
		require(revisions.revision(0, 0, 257) == initial, "stationary samples reuse their revision");
		revisions.recordMutation(10_000, 10_000);
		require(revisions.revision(0, 0, 257) == initial, "distant unobserved mutations retain the cache");
		revisions.revision(10_000, 10_000, 257);
		revisions.recordMutation(10_000, 10_000);
		require(revisions.revision(0, 0, 257) == initial, "another agent's distant mutations retain the cache");
		revisions.recordMutation(0, 0);
		long firstWrite = revisions.revision(0, 0, 257);
		require(firstWrite > initial, "nearby mutation invalidates candidates");
		revisions.recordMutation(0, 0);
		long secondWrite = revisions.revision(0, 0, 257);
		require(secondWrite > firstWrite, "every write invalidates even without a tick advance");
		revisions.recordMutation(-256, -256);
		require(revisions.revision(0, 0, 257) > secondWrite, "a previously older region cannot hide a mutation behind the maximum");
		long boundary = revisions.revision(256, 256, 257);
		revisions.recordMutation(513, 513);
		require(revisions.revision(256, 256, 257) > boundary, "neighboring shape reach crosses positive region boundaries");
		long negativeBoundary = revisions.revision(-256, -256, 257);
		revisions.recordMutation(-513, -513);
		require(revisions.revision(-256, -256, 257) > negativeBoundary, "negative region boundaries use floor coordinates");
		long beforeEviction = revisions.revision(0, 0, 257);
		for (int index = 1; index <= 300; index++) revisions.revision(index * 2_048, 0, 257);
		require(revisions.retainedRegions() <= 256, "travel retains a bounded regional index");
		revisions.recordMutation(0, 0);
		require(revisions.revision(0, 0, 257) > beforeEviction, "revisiting an evicted region cannot revive stale candidates");
		return 11;
	}

	private static void require(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
