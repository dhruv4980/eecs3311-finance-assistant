package com.financeassistant.goal;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.LLMClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/** F08 Savings goal management: create goals, record contributions, compute progress. */
public class GoalService {
    static final double MAX_TARGET = 10_000_000;

    private final DatabaseManager db = DatabaseManager.getInstance();
    private final GoalState completed = new CompletedState();
    private final GoalState inProgress;
    private final Clock clock;

    public GoalService(LLMClient llm) {
        this(llm, Clock.systemDefaultZone());
    }

    /** The clock is injectable so tests can fix "today". */
    public GoalService(LLMClient llm, Clock clock) {
        this.inProgress = new InProgressState(llm, completed);
        this.clock = clock;
    }

    public Goal createGoal(String name, double target, LocalDate deadline) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give the goal a name.");
        }
        if (!(target > 0)) {
            throw new IllegalArgumentException("The target must be more than $0.");
        }
        if (target > MAX_TARGET) {
            throw new IllegalArgumentException("The target must be at most $" + (long) MAX_TARGET + ".");
        }
        if (deadline == null || !deadline.isAfter(today())) {
            throw new IllegalArgumentException("The deadline must be a date after today.");
        }
        Goal goal = new Goal(name.strip(), Math.round(target * 100) / 100.0, deadline, today());
        goal.updateState(inProgress, completed);
        return db.save(goal);
    }

    /** Adds money to a goal; reaching the target completes it. */
    public Goal addContribution(int goalId, double amount) {
        if (!(amount > 0)) {
            throw new IllegalArgumentException("A contribution must be more than $0.");
        }
        Goal goal = getGoal(goalId);
        goal.addContribution(amount); // throws if the goal is already completed
        db.save(new GoalContribution(goalId, today(), Math.round(amount * 100) / 100.0));
        return db.save(goal);
    }

    public Goal getGoal(int goalId) {
        Goal goal = db.find(Goal.class, goalId)
                .orElseThrow(() -> new IllegalArgumentException("No goal with id " + goalId));
        goal.updateState(inProgress, completed);
        return goal;
    }

    /** All goals: in progress first, nearest deadline first. */
    public List<Goal> getGoals() {
        List<Goal> goals = db.findAll(Goal.class);
        goals.forEach(g -> g.updateState(inProgress, completed));
        return goals.stream()
                .sorted(Comparator.comparing(Goal::isCompleted).thenComparing(Goal::getDeadline))
                .toList();
    }

    public boolean deleteGoal(int goalId) {
        for (GoalContribution c : getContributions(goalId)) {
            db.delete(GoalContribution.class, c.getId());
        }
        return db.delete(Goal.class, goalId);
    }

    public List<GoalContribution> getContributions(int goalId) {
        return db.find(GoalContribution.class, c -> c.getGoalId() == goalId).stream()
                .sorted(Comparator.comparing(GoalContribution::getDate)).toList();
    }

    /** Progress figures, all computed deterministically. */
    public GoalProgress getProgress(Goal goal) {
        LocalDate today = today();
        double remaining = Math.max(0, round(goal.getTargetAmount() - goal.getContributed()));
        double percent = Math.min(100, Math.round(goal.getContributed() / goal.getTargetAmount() * 1000) / 10.0);
        long monthsLeft = Math.max(0, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), goal.getDeadline().withDayOfMonth(1)));
        double required = !goal.showsRequiredContribution() ? 0 : round(remaining / Math.max(1, monthsLeft));

        // saving pace: everything contributed, spread over the months since the goal was created (at least one)
        long monthsActive = Math.max(1, ChronoUnit.MONTHS.between(goal.getCreatedDate(), today) + 1);
        double average = round(goal.getContributed() / monthsActive);
        LocalDate estimate = null;
        if (goal.isCompleted()) {
            estimate = getContributions(goal.getId()).stream().map(GoalContribution::getDate)
                    .max(Comparator.naturalOrder()).orElse(today);
        } else if (average > 0) {
            estimate = today.plusMonths((long) Math.ceil(remaining / average));
        }
        boolean onTrack = goal.isCompleted() || (estimate != null && !estimate.isAfter(goal.getDeadline()));
        return new GoalProgress(percent, remaining, monthsLeft, required, average, estimate, onTrack);
    }

    /** The state-specific message (AI coaching while in progress, congratulations once completed). */
    public String getMessage(Goal goal) {
        return goal.getMessage(getProgress(goal));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
