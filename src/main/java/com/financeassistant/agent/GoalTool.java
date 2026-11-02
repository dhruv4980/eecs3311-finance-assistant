package com.financeassistant.agent;

import com.financeassistant.goal.Goal;
import com.financeassistant.goal.GoalProgress;
import com.financeassistant.goal.GoalService;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The user's savings goals with progress, required monthly saving and whether they are on track. */
public class GoalTool implements Tool {
    private final GoalService goalService;
    private final Gson gson = new Gson();

    public GoalTool(GoalService goalService) {
        this.goalService = goalService;
    }

    @Override
    public String getName() {
        return "get_goals";
    }

    @Override
    public String getDescription() {
        return "Savings goals: target, deadline, amount saved, % complete, amount needed per month, "
                + "average saved per month, estimated completion date and whether the goal is on track.";
    }

    @Override
    public String getParameters() {
        return "none";
    }

    @Override
    public String execute(Map<String, Object> args) {
        List<Map<String, Object>> rows = goalService.getGoals().stream().map(g -> {
            GoalProgress p = goalService.getProgress(g);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", g.getName());
            row.put("status", g.isCompleted() ? "completed" : "in progress");
            row.put("target", g.getTargetAmount());
            row.put("deadline", g.getDeadline().toString());
            row.put("saved", g.getContributed());
            row.put("percent", p.percent());
            row.put("remaining", p.remaining());
            if (g.showsRequiredContribution()) {
                row.put("neededPerMonth", p.requiredMonthly());
            }
            row.put("averageSavedPerMonth", p.averageMonthly());
            row.put("estimatedCompletion", String.valueOf(p.estimatedCompletion()));
            row.put("onTrack", p.onTrack());
            return row;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goals", rows);
        if (rows.isEmpty()) {
            result.put("note", "The user has no savings goals yet.");
        }
        return gson.toJson(result);
    }
}
