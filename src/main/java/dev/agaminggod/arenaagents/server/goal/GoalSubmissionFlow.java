package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class GoalSubmissionFlow {
	private GoalSubmissionFlow() {
	}

	public static GoalSubmission route(
			GoalCompilation compilation,
			Function<GoalSpec, AgentTransition> activate,
			Supplier<PendingGoalDraft> draftFactory,
			Consumer<PendingGoalDraft> stageDraft,
			GoalSpecRequestSink publishDraft
	) {
		Objects.requireNonNull(compilation, "compilation must not be null");
		Objects.requireNonNull(activate, "activate must not be null");
		Objects.requireNonNull(draftFactory, "draftFactory must not be null");
		Objects.requireNonNull(stageDraft, "stageDraft must not be null");
		Objects.requireNonNull(publishDraft, "publishDraft must not be null");
		return switch (compilation.kind()) {
			case ACCEPTED -> GoalSubmission.activated(activate.apply(compilation.acceptedSpec().orElseThrow()));
			case NEEDS_TRANSLATION -> {
				PendingGoalDraft draft = Objects.requireNonNull(draftFactory.get(), "draftFactory returned no draft");
				stageDraft.accept(draft);
				publishDraft.publish(draft);
				yield GoalSubmission.pending(draft);
			}
			case REJECTED -> throw new AgentDomainException(
					"GOAL_REQUIRES_CLARIFICATION", compilation.playerMessage());
		};
	}
}
