package com.financeassistant.goal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Before;
import org.junit.Test;

public class GoalServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private FakeLLMClient llm;
    private GoalService service;

    private static Clock at(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    @Before
    public void setUp() {
        DatabaseManager.open(null);
        llm = new FakeLLMClient(prompt -> "AI: " + prompt.substring(0, 20));
        service = new GoalService(llm, at(TODAY));
    }

    @Test
    public void newGoalNeedsTheTargetSpreadOverTheMonthsLeft() {
        Goal laptop = service.createGoal("Laptop", 1200, LocalDate.of(2027, 5, 1));
        GoalProgress p = service.getProgress(laptop);
        assertTrue(laptop.getState() instanceof InProgressState);
        assertEquals(6, p.monthsLeft());
        assertEquals(200.0, p.requiredMonthly(), 0.001);
        assertEquals(0.0, p.percent(), 0.001);
        assertNull(p.estimatedCompletion()); // nothing saved yet
        assertFalse(p.onTrack());
    }

    @Test
    public void contributionsUpdateProgressAndPace() {
        Goal laptop = service.createGoal("Laptop", 1200, LocalDate.of(2027, 5, 1));
        service.addContribution(laptop.getId(), 300);
        GoalProgress p = service.getProgress(service.getGoal(laptop.getId()));
        assertEquals(25.0, p.percent(), 0.001);
        assertEquals(900.0, p.remaining(), 0.001);
        assertEquals(150.0, p.requiredMonthly(), 0.001);
        assertEquals(300.0, p.averageMonthly(), 0.001);
        assertEquals(LocalDate.of(2027, 2, 1), p.estimatedCompletion());
        assertTrue(p.onTrack());
        assertEquals(1, service.getContributions(laptop.getId()).size());
    }

    @Test
    public void slowSavingIsBehindSchedule() {
        Goal trip = service.createGoal("Trip", 3000, LocalDate.of(2027, 2, 1));
        GoalService later = new GoalService(llm, at(TODAY.plusMonths(1)));
        later.addContribution(trip.getId(), 100);
        GoalProgress p = later.getProgress(later.getGoal(trip.getId()));
        assertEquals(50.0, p.averageMonthly(), 0.001); // $100 over 2 months
        assertFalse(p.onTrack());
    }

    @Test
    public void reachingTheTargetSwitchesToCompletedState() {
        Goal goal = service.createGoal("Headphones", 200, LocalDate.of(2027, 1, 1));
        service.addContribution(goal.getId(), 150);
        Goal done = service.addContribution(goal.getId(), 50);

        assertTrue(done.isCompleted());
        assertTrue(done.getState() instanceof CompletedState);
        assertFalse(done.showsRequiredContribution());
        assertEquals(0.0, service.getProgress(done).requiredMonthly(), 0.001);
        assertTrue(service.getMessage(done).startsWith("Goal reached!"));
        int callsBefore = llm.prompts.size();
        service.getMessage(done);
        assertEquals(callsBefore, llm.prompts.size()); // completed goals do not call the AI

        try {
            service.addContribution(goal.getId(), 10);
            fail("completed goals take no more contributions");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already completed"));
        }
    }

    @Test
    public void inProgressMessageComesFromTheAiWithTheComputedFacts() {
        Goal goal = service.createGoal("Laptop", 1200, LocalDate.of(2027, 5, 1));
        service.addContribution(goal.getId(), 300);
        String message = service.getMessage(service.getGoal(goal.getId()));
        assertTrue(message.startsWith("AI: "));
        String prompt = llm.prompts.get(llm.prompts.size() - 1);
        assertTrue(prompt, prompt.contains("Needed per month from now: $150.00"));
        assertTrue(prompt.contains("which is on time"));
    }

    @Test
    public void templateMessageWhenAiIsDown() {
        GoalService offline = new GoalService(FakeLLMClient.failing("down"), at(TODAY));
        Goal goal = offline.createGoal("Laptop", 1200, LocalDate.of(2027, 5, 1));
        String message = offline.getMessage(goal);
        assertTrue(message, message.contains("Save $200.00 a month"));
        assertTrue(message.contains("Add a first contribution"));
    }

    @Test
    public void completedStateSurvivesARestart() throws Exception {
        Path file = Files.createTempDirectory("goals").resolve("db.json");
        DatabaseManager.open(file);
        GoalService first = new GoalService(llm, at(TODAY));
        Goal goal = first.createGoal("Bike", 100, LocalDate.of(2027, 1, 1));
        first.addContribution(goal.getId(), 100);

        DatabaseManager.open(file);
        Goal reloaded = new GoalService(llm, at(TODAY)).getGoal(goal.getId());
        assertTrue(reloaded.getState() instanceof CompletedState);
        assertEquals(100.0, reloaded.getContributed(), 0.001);
    }

    @Test
    public void statesAreSharedNotRecreatedPerGoal() {
        Goal a = service.createGoal("A", 100, LocalDate.of(2027, 1, 1));
        Goal b = service.createGoal("B", 100, LocalDate.of(2027, 1, 1));
        assertSame(service.getGoal(a.getId()).getState(), service.getGoal(b.getId()).getState());
    }

    @Test
    public void invalidInputIsRejected() {
        expectInvalid(() -> service.createGoal("Car", 0, LocalDate.of(2027, 1, 1)), "more than $0");
        expectInvalid(() -> service.createGoal("Car", -5, LocalDate.of(2027, 1, 1)), "more than $0");
        expectInvalid(() -> service.createGoal(" ", 100, LocalDate.of(2027, 1, 1)), "name");
        expectInvalid(() -> service.createGoal("Car", 100, TODAY), "after today");
        Goal goal = service.createGoal("Car", 100, LocalDate.of(2027, 1, 1));
        expectInvalid(() -> service.addContribution(goal.getId(), 0), "more than $0");
        expectInvalid(() -> service.addContribution(999, 10), "No goal");
    }

    @Test
    public void deleteRemovesGoalAndItsContributions() {
        Goal goal = service.createGoal("Car", 1000, LocalDate.of(2027, 1, 1));
        service.addContribution(goal.getId(), 10);
        assertTrue(service.deleteGoal(goal.getId()));
        assertTrue(service.getGoals().isEmpty());
        assertTrue(service.getContributions(goal.getId()).isEmpty());
    }

    private static void expectInvalid(Runnable action, String message) {
        try {
            action.run();
            fail("expected: " + message);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }
}
